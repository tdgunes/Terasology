/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.opengl;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Set;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.config.Config;
import org.terasology.config.RenderingConfig;
import org.terasology.registry.CoreRegistry;
import org.terasology.rendering.dag.FBOManagerSubscriber;
import org.terasology.rendering.oculusVr.OculusVrHelper;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * The FrameBuffersManager generates and maintains a number of Frame Buffer Objects (FBOs) used throughout the
 * rendering engine.
 *
 * In most instances Frame Buffers can be thought of as 2D arrays of pixels in GPU memory: shaders write to them or
 * read from them. Some buffers are static and never change for the lifetime of the manager. Some buffers are dynamic:
 * they get disposed and regenerated, i.e. in case the display resolution changes. Some buffers hold intermediate
 * steps of the rendering process and the content of one buffer, "sceneFinal", is eventually sent to the display.
 * <br/>
 * At this stage no buffer can be added or deleted: the list of buffers and their characteristics is hardcoded.
 * <br/>
 * The existing set of public methods is primarily intended to allow communication between this manager and other parts
 * of the rendering engine, most notably the PostProcessor and the GraphicState instances and the shaders system.
 * <br/>
 * An important exception is the takeScreenshot() method which prompts the renderer to eventually (not immediately)
 * redirect its output to a file. This is the only public method that is intended to be used from outside the
 * rendering engine.
 */
public class FrameBuffersManager {
    private static final Logger logger = LoggerFactory.getLogger(FrameBuffersManager.class);

    private PBO frontReadbackPBO;   // PBOs are 1x1 pixels buffers used to read GPU data back
    private PBO backReadbackPBO;    // into the CPU. This data is then used in the context of
    private PBO currentReadbackPBO; // eye adaptation.

    private FBO sceneOpaque;
    private FBO sceneShadowMap;

    // I could have named them fullResolution, halfResolution and so on. But halfScale is actually
    // -both- fullScale's dimensions halved, leading to -a quarter- of its resolution. Following
    // this logic one32thScale would have to be named one1024thResolution and the otherwise
    // straightforward connection between variable names and dimensions would have been lost. -- manu3d
    private Dimensions fullScale = new Dimensions();
    private Dimensions halfScale = new Dimensions();
    private Dimensions quarterScale = new Dimensions();
    private Dimensions one8thScale = new Dimensions();
    private Dimensions one16thScale = new Dimensions();
    private Dimensions one32thScale = new Dimensions();

    private Map<Float, Dimensions> dimensionsMap;

    // Note: this assumes that the settings in the configs might change at runtime,
    // but the config objects will not. At some point this might change, i.e. implementing presets.
    private Config config = CoreRegistry.get(Config.class);
    private RenderingConfig renderingConfig = config.getRendering();

    private Map<String, FBOManager> fboBuilderMap = Maps.newHashMap();

    private PostProcessor postProcessor;
    private Set<FBOManagerSubscriber> fboManagerSubscribers;
    private Set<FBOManager> dynamicFBOManagers = Sets.newHashSet();

    public FrameBuffersManager() {
        // nothing to do here, everything happens at initialization time,
        // after GraphicState and PostProcessors have been set
    }

    /**
    Initializes the FrameBuffersManager instance by creating static FBOs, dynamic FBOs and shadowMap FBO.
    Also instructs the PostProcessor and the GraphicState instances to fetch the FBOs they require.
     */
    public void initialize() {
        fullScale = new Dimensions();
        halfScale = new Dimensions();
        quarterScale = new Dimensions();
        one8thScale = new Dimensions();
        one16thScale = new Dimensions();
        one32thScale = new Dimensions();

        setScales();

        // FIXME: dirty, scheduled for removal
        FBOManager.setFrameBuffersManager(this);
        dimensionsMap = Maps.newHashMap();

        fboManagerSubscribers = Sets.newHashSet();

        createStaticFBOs();

        setDynamicFBOsDimensions();
        createDynamicFBOs();

        createShadowMapFBO();
    }

    // Static FBOs do not change during the lifetime of a FrameBuffersManager instance.
    // They are used to progressively downsample the image all the way into a 1x1 buffer
    // holding average image brightness data. This is then used in the context of eye adaptation.
    // TODO: This will be eliminated
    private void createStaticFBOs() {
        addStaticFBO("scene16", new FBOManager(16, 16, FBO.Type.DEFAULT));
        addStaticFBO("scene8", new FBOManager(8, 8, FBO.Type.DEFAULT));
        addStaticFBO("scene4", new FBOManager(4, 4, FBO.Type.DEFAULT));
        addStaticFBO("scene2", new FBOManager(2, 2, FBO.Type.DEFAULT));
        addStaticFBO("scene1", new FBOManager(1, 1, FBO.Type.DEFAULT));

        // Technically these are not Frame Buffer Objects but Pixel Buffer Objects.
        // Their instantiation and assignments are done here because they are static buffers.
        frontReadbackPBO = new PBO(1, 1);
        backReadbackPBO = new PBO(1, 1);
        currentReadbackPBO = frontReadbackPBO;
    }

    private void setDynamicFBOsDimensions() {
        refreshDynamicFBOsDimensions();
    }

    // TODO: This will be eliminated
    private void createDynamicFBOs() {
        sceneOpaque = addDynamicFBO("sceneOpaque", new FBOManager(fullScale, FBO.Type.HDR).useDepthBuffer().useNormalBuffer().useLightBuffer().useStencilBuffer());
        addDynamicFBO("sceneOpaquePingPong", new FBOManager(fullScale, FBO.Type.HDR).useDepthBuffer().useNormalBuffer().useLightBuffer().useStencilBuffer());

        addDynamicFBO("sceneSkyBand0", new FBOManager(one16thScale, FBO.Type.DEFAULT));
        addDynamicFBO("sceneSkyBand1", new FBOManager(one32thScale, FBO.Type.DEFAULT));

        FBO sceneReflectiveRefractive = addDynamicFBO("sceneReflectiveRefractive", new FBOManager(fullScale, FBO.Type.HDR).useNormalBuffer());
        sceneOpaque.attachDepthBufferTo(sceneReflectiveRefractive);

        addDynamicFBO("sceneReflected", new FBOManager(halfScale, FBO.Type.DEFAULT).useDepthBuffer());

        addDynamicFBO("outline", new FBOManager(fullScale, FBO.Type.DEFAULT));
        addDynamicFBO("ssao", new FBOManager(fullScale, FBO.Type.DEFAULT));
        addDynamicFBO("ssaoBlurred", new FBOManager(fullScale, FBO.Type.DEFAULT));
        addDynamicFBO("scenePrePost", new FBOManager(fullScale, FBO.Type.HDR));

        addDynamicFBO("lightShafts", new FBOManager(halfScale, FBO.Type.DEFAULT));

        addDynamicFBO("sceneHighPass", new FBOManager(fullScale, FBO.Type.DEFAULT));
        addDynamicFBO("sceneBloom0", new FBOManager(halfScale, FBO.Type.DEFAULT));
        addDynamicFBO("sceneBloom1", new FBOManager(quarterScale, FBO.Type.DEFAULT));
        addDynamicFBO("sceneBloom2", new FBOManager(one8thScale, FBO.Type.DEFAULT));

        addDynamicFBO("sceneBlur0", new FBOManager(halfScale, FBO.Type.DEFAULT));
        addDynamicFBO("sceneBlur1", new FBOManager(halfScale, FBO.Type.DEFAULT));

        addDynamicFBO("ocUndistorted", new FBOManager(fullScale, FBO.Type.DEFAULT));
        addDynamicFBO("sceneFinal", new FBOManager(fullScale, FBO.Type.DEFAULT));

    }

    private void createShadowMapFBO() {
        recreateShadowMapFBO();
    }

    /**
     * Executed before any rendering begins, this method disposes and regenerates a number of FBOs if size changes
     * have been triggered. Also prompts the GraphicState and PostProcessor instances to refresh their internal
     * references to use the new FBOs.
     */
    public void preRenderUpdate() {
        refreshDynamicFBOsDimensions();
        if (sceneOpaque.dimensions().areDifferentFrom(fullScale)) {
            disposeOfAllDynamicFBOs();
            recreateDynamicFBOs();
        }

        if (sceneShadowMap != null && sceneShadowMap.width() != renderingConfig.getShadowMapResolution()) {
            recreateShadowMapFBO();
        }
    }

    // FIXME: work on this method to reduce all scales
    private void refreshDynamicFBOsDimensions() {
        Dimensions oldFullScale = new Dimensions(fullScale);

        if (postProcessor.isNotTakingScreenshot()) {
            fullScale.update(Display.getWidth(), Display.getHeight());
            if (renderingConfig.isOculusVrSupport()) {
                fullScale.multiplySelfBy(OculusVrHelper.getScaleFactor());
            }
        } else {
            fullScale.update(renderingConfig.getScreenshotSize().getWidth(Display.getWidth()),
                    renderingConfig.getScreenshotSize().getHeight(Display.getHeight()));
        }

        fullScale.multiplySelfBy(renderingConfig.getFboScale() / 100f);

        if (fullScale.isDifferentFrom(oldFullScale)) {
            // No operator overloading :(
            setScales();
        }

        for (Map.Entry<Float, Dimensions> entry : dimensionsMap.entrySet()) {
            float scale = entry.getKey();
            Dimensions dimensions = entry.getValue();
            dimensions.multiplySelfBy(scale);
        }
    }

    private void setScales() {
        halfScale.set(fullScale).divideSelfBy(2);
        quarterScale.set(fullScale).divideSelfBy(4);
        one8thScale.set(fullScale).divideSelfBy(8);
        one16thScale.set(fullScale).divideSelfBy(16);
        one32thScale.set(fullScale).divideSelfBy(32);
    }

    // providing a rough guide of each FBO here, as the method that creates them (recreateDynamicFBOs) is quite dense already
    private void disposeOfAllDynamicFBOs() {

        for (FBOManager dynamicFBOManager : dynamicFBOManagers) {
            dynamicFBOManager.disposeFBO();
        }


//        deleteFBO("sceneOpaque");           // Primary FBO: most visual information eventually ends up here
//        deleteFBO("sceneOpaquePingPong");   // The sceneOpaque FBOs are swapped every frame, to use one for reading and the other for writing
//                                            // Notice that these two FBOs hold a number of buffers, for color, depth, normals, etc.
//
//        deleteFBO("sceneSkyBand0"); // two buffers used to generate a depth cue: things in the distance fades into the atmosphere's color.
//        deleteFBO("sceneSkyBand1");
//
//        deleteFBO("sceneReflectiveRefractive"); // used to render reflective and refractive surfaces, most obvious case being the water surface
//
//        deleteFBO("sceneReflected"); // the water surface displays a reflected version of the scene. This version is stored here.
//
//        deleteFBO("outline");       // greyscale depth-based rendering of object outlines
//        deleteFBO("ssao");          // greyscale screen-space ambient occlusion rendering
//        deleteFBO("ssaoBlurred");   // greyscale screen-space ambient occlusion rendering - blurred version
//        deleteFBO("scenePrePost");  // intermediate step, combining a number of renderings made available so far
//                                    // into one buffer to be post-processed.
//
//        deleteFBO("lightShafts");       // light shafts rendering
//
//        deleteFBO("sceneHighPass"); // a number of buffers to create the bloom effect
//        deleteFBO("sceneBloom0");
//        deleteFBO("sceneBloom1");
//        deleteFBO("sceneBloom2");
//
//        deleteFBO("sceneBlur0");    // a pair of buffers holding blurred versions of the rendered scene,
//        deleteFBO("sceneBlur1");    // also used for the bloom effect, but not only.
//
//        deleteFBO("ocUndistorted"); // if OculusRift support is enabled this buffer holds the side-by-side views
//                                    // for each eye, with no lens distortion applied.
//
//        deleteFBO("sceneFinal");    // the content of this buffer is eventually shown on the display or sent to a file if taking a screenshot

    }

    private void recreateDynamicFBOs() {

        for (FBOManager dynamicFBOManager : dynamicFBOManagers) {
            dynamicFBOManager.build();
        }



        notifySubscribers();
    }

    private void notifySubscribers() {
        for (FBOManagerSubscriber subscriber : fboManagerSubscribers) {
            subscriber.update();
        }
    }

    private void recreateShadowMapFBO() {
        int shadowMapResFromSettings = renderingConfig.getShadowMapResolution();
        Dimensions shadowMapResolution =  new Dimensions(shadowMapResFromSettings, shadowMapResFromSettings);
        sceneShadowMap = addDynamicFBO("sceneShadowMap", new FBOManager(shadowMapResolution, FBO.Type.NO_COLOR).useDepthBuffer());
        handleDisposedShadowMap(sceneShadowMap);
    }

    private void handleDisposedShadowMap(FBO fbo) {
        if (fbo.getStatus() == FBO.Status.DISPOSED) {
            logger.warn("Failed to generate ShadowMap FBO. Turning off shadows.");
            renderingConfig.setDynamicShadows(false);
        }
    }

    /**
     * Returns the content of the color buffer of the FBO "sceneFinal", from GPU memory as a ByteBuffer.
     * If the FBO "sceneFinal" is unavailable, returns null.
     *
     * @return a ByteBuffer or null
     */
    public ByteBuffer getSceneFinalRawData() {
        FBO fboSceneFinal = getFBO("sceneFinal");
        if (fboSceneFinal == null) {
            logger.error("FBO sceneFinal is unavailable: cannot return data from it.");
            return null;
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(fboSceneFinal.width() * fboSceneFinal.height() * 4);

        fboSceneFinal.bindTexture();
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        FBO.unbindTexture();

        return buffer;
    }

    /**
     * Returns an FBO given its name.
     *
     * If no FBO maps to the given name, null is returned and an error is logged.
     *
     * @param fboName The name of the FBO
     * @return an FBO or null
     */
    public FBO getFBO(String fboName) {

        FBOManager fboManager = fboBuilderMap.get(fboName);

        if (fboManager == null) {
            logger.error("Failed to retrieve FBOManager '" + fboName + "'!");
            return null;
        }

        FBO fbo = fboManager.getGeneratedFBO();
        if (fbo == null) {
            logger.error("FBOManager's FBO '" + fboName + "' is not built!");
        }

        return fbo;
    }


    public FBOManager getFBOManager(String fboName) {
        return fboBuilderMap.get(fboName);
    }


    public void deleteFBO(String identifier) {
        FBO fbo = getFBO(identifier);

        if (fbo != null) {
            fbo.dispose();
            FBOManager fboManager = fboBuilderMap.remove(identifier);
            dynamicFBOManagers.remove(fboManager);

        } else {
            logger.error("Failed to delete FBO '" + identifier + "': it doesn't exist!");
        }
    }

    /**
     * Binds the color texture of the FBO with the given name and returns true.
     *
     * If no FBO is associated with the given name, false is returned and an error is logged.
     *
     * @param fboName the name of an FBO
     * @return True if an FBO associated with the given name exists. False otherwise.
     */
    public boolean bindFboColorTexture(String fboName) {
        FBO fbo = getFBO(fboName);

        if (fbo != null) {
            fbo.bindTexture();
            return true;
        }

        logger.error("Failed to bind FBO color texture since the requested " + fboName + " FBO could not be found!");
        return false;
    }

    /**
     * Binds the depth texture of the FBO with the given name and returns true.
     *
     * If no FBO is associated with the given name, false is returned and an error is logged.
     *
     * @param fboName the name of an FBO
     * @return True if an FBO associated with the given name exists. False otherwise.
     */
    public boolean bindFboDepthTexture(String fboName) {
        FBO fbo = getFBO(fboName);

        if (fbo != null) {
            fbo.bindDepthTexture();
            return true;
        }

        logger.error("Failed to bind FBO depth texture since the requested " + fboName + " FBO could not be found!");
        return false;
    }

    /**
     * Binds the normals texture of the FBO with the given name and returns true.
     *
     * If no FBO is associated with the given name, false is returned and an error is logged.
     *
     * @param fboName the name of an FBO
     * @return True if an FBO associated with the given name exists. False otherwise.
     */
    public boolean bindFboNormalsTexture(String fboName) {
        FBO fbo = getFBO(fboName);

        if (fbo != null) {
            fbo.bindNormalsTexture();
            return true;
        }

        logger.error("Failed to bind FBO normals texture since the requested " + fboName + " FBO could not be found!");
        return false;
    }

    /**
     * Binds the light buffer texture of the FBO with the given name and returns true.
     *
     * If no FBO is associated with the given name, false is returned and an error is logged.
     *
     * @param fboName the name of an FBO
     * @return True if an FBO associated with the given name exists. False otherwise.
     */
    public boolean bindFboLightBufferTexture(String fboName) {
        FBO fbo = getFBO(fboName);

        if (fbo != null) {
            fbo.bindLightBufferTexture();
            return true;
        }

        logger.error("Failed to bind FBO light buffer texture since the requested " + fboName + " FBO could not be found!");
        return false;
    }

    /**
     * Swaps the sceneOpaque FBOs, so that the one previously used for writing is now used for reading and viceversa.
     */
    public void swapSceneOpaqueFBOs() {
        FBOManager currentSceneOpaquePingPongManager = getFBOManager("sceneOpaquePingPong");
        FBOManager sceneOpaqueManager = getFBOManager("sceneOpaque");

        currentSceneOpaquePingPongManager.setGeneratedFBO(sceneOpaqueManager.getGeneratedFBO());
        sceneOpaqueManager.setGeneratedFBO(currentSceneOpaquePingPongManager.getGeneratedFBO());
    }

    /**
     * Swaps the readback PBOs, so that the one previously used for writing is now used for reading and viceversa.
     */
    public void swapReadbackPBOs() {
        if (currentReadbackPBO == frontReadbackPBO) {
            currentReadbackPBO = backReadbackPBO;
        } else {
            currentReadbackPBO = frontReadbackPBO;
        }
    }

    /**
     * Returns the current readback PBO, the one that will be used for reading from.
     * @return the current readback PBO
     */
    public PBO getCurrentReadbackPBO() {
        return currentReadbackPBO;
    }

    /**
     * Sets an internal reference to the PostProcessor instance. This reference is used to inform the PostProcessor
     * instance that changes have occurred and that it should refresh its references to the FBOs it uses.
     *
     * @param postProcessor a PostProcessor instance
     */
    public void setPostProcessor(PostProcessor postProcessor) {
        this.postProcessor = postProcessor;
    }

    /**
     * TODO: add javadocs
     *
     * @param subscriber
     */
    public boolean subscribe(FBOManagerSubscriber subscriber) {
        return fboManagerSubscribers.add(subscriber);
    }

    /**
     * TODO: add javadocs
     *
     * @param subscriber
     * @return
     */
    public boolean unsubscribe(FBOManagerSubscriber subscriber) {
        return fboManagerSubscribers.remove(subscriber);
    }


    public Dimensions getScale(float scale) {
        if (dimensionsMap.containsKey(scale)) {
            return dimensionsMap.get(scale);
        }
        Dimensions dimensions = new Dimensions(fullScale);
        dimensionsMap.put(scale, fullScale);
        return dimensions;
    }

    private FBO addFBO(String fboName, FBOManager fboManager, boolean isDynamic) {
        fboManager.setTitle(fboName);
        // FIXME: defensive programming here
        if (!fboManager.isFBOBuilt()) {
            fboManager.build();
        }
        fboBuilderMap.put(fboName, fboManager);
        if (isDynamic) {
            dynamicFBOManagers.add(fboManager);
        }
        return fboManager.getGeneratedFBO();
    }

    public FBO addStaticFBO(String fboName, FBOManager fboManager) {
        return addFBO(fboName, fboManager, false);
    }

    public FBO addDynamicFBO(String fboName, FBOManager fboManager) {
        return addFBO(fboName, fboManager, true);
    }

    public boolean isFBOAvailable(String fboName) {
        return fboBuilderMap.containsKey(fboName);
    }
}

