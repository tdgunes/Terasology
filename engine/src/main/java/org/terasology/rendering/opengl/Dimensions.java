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

/**
 * TODO: Add javadocs
 */

/**
 * Support class wrapping width and height of FBOs. Also provides some ad-hoc methods to make code more readable.
 */
public class Dimensions {
    private int width;
    private int height;

    /**
     * Standard Constructor - returns a Dimensions object.
     *
     * @param width  An integer, representing the width of the FBO in pixels.
     * @param height An integer, representing the height of the FBO in pixels.
     */
    public Dimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     *
     */
    public Dimensions() {
        this(0, 0);
    }

    /**
     * @param dimensions
     */
    public Dimensions(Dimensions dimensions) {
        this(dimensions.width(), dimensions.height());
    }

    public void update(int w, int h) {
        this.width = w;
        this.height = h;
    }

    /**
     * TODO: add java docs
     *
     * @param divisor An integer.
     */
    public void divideSelfBy(int divisor) {
        System.out.println(String.format("%s/%s %s/%s ", width(), divisor, height(), divisor));
        this.width = width / divisor;
        this.height = height / divisor;
        System.out.println(String.format("result: %s, %s", width(), height()));
    }

    /**
     * Multiplies (in place) both width and height of this Dimensions object by multiplier.
     *
     * @param multiplier A float representing a multiplication factor.
     */
    public void multiplySelfBy(float multiplier) {
        width *= multiplier;
        height *= multiplier;
    }

    /**
     * Returns true if the other instance of this class is null or has different width/height.
     * Similar to the more standard equals(), doesn't bother with checking if -other- is an instance
     * of Dimensions. It also makes for more readable code, i.e.:
     * <p>
     * newDimensions.areDifferentFrom(oldDimensions)
     *
     * @param other A Dimensions object
     * @return True if the two objects are different as defined above.
     */
    public boolean areDifferentFrom(Dimensions other) {
        return other == null || this.width != other.width || this.height != other.height;
    }

    /**
     * Identical in behaviour to areDifferentFrom(Dimensions other),
     * in some situation can be more semantically appropriate, i.e.:
     * <p>
     * newResolution.isDifferentFrom(oldResolution);
     *
     * @param other A Dimensions object.
     * @return True if the two objects are different as defined in the javadoc for areDifferentFrom(other).
     */
    public boolean isDifferentFrom(Dimensions other) {
        return areDifferentFrom(other);
    }

    /**
     * Returns the width.
     *
     * @return An integer representing the width stored in the Dimensions instance.
     */
    public int width() {
        return this.width;
    }

    /**
     * Returns the height.
     *
     * @return An integer representing the height stored in the Dimensions instance.
     */
    public int height() {
        return this.height;
    }

    public Dimensions set(Dimensions dimensions) {
        this.width = dimensions.width();
        this.height = dimensions.height();
        return this;
    }

    @Override
    public String toString() {
        return String.format("(%s,%s)", width(), height());
    }
}
