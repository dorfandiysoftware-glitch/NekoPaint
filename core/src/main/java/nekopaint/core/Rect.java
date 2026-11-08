// SPDX-License-Identifier: GPL-3.0-or-later
package nekopaint.core;

/** An immutable integer rectangle. {@code right} and {@code bottom} are exclusive. */
public final class Rect {

    /** The empty rectangle. */
    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    /**
     * @throws IllegalArgumentException if {@code right < left} or {@code bottom < top}
     */
    public Rect(int left, int top, int right, int bottom) {
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Rectangle has negative size");
        }
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public boolean isEmpty() {
        return right <= left || bottom <= top;
    }

    /** The smallest rectangle containing both. An empty operand is ignored. */
    public Rect union(Rect other) {
        if (isEmpty()) {
            return other;
        }
        if (other.isEmpty()) {
            return this;
        }
        return new Rect(Math.min(left, other.left), Math.min(top, other.top),
                Math.max(right, other.right), Math.max(bottom, other.bottom));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rect)) {
            return false;
        }
        Rect r = (Rect) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override
    public int hashCode() {
        int h = left;
        h = 31 * h + top;
        h = 31 * h + right;
        return 31 * h + bottom;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + ", " + right + ", " + bottom + ")";
    }
}
