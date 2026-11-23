// SPDX-License-Identifier: GPL-3.0-or-later
using System;

namespace NekoPaint.Core
{
    /// <summary>Integer rectangle, right/bottom exclusive.</summary>
    public sealed class Rect : IEquatable<Rect>
    {
        public static readonly Rect Empty = new Rect(0, 0, 0, 0);

        public readonly int Left, Top, Right, Bottom;

        public Rect(int left, int top, int right, int bottom)
        {
            if (right < left || bottom < top) throw new ArgumentException("Rectangle has negative size");
            Left = left; Top = top; Right = right; Bottom = bottom;
        }

        public int Width => Right - Left;
        public int Height => Bottom - Top;
        public bool IsEmpty => Right <= Left || Bottom <= Top;

        public Rect Union(Rect other)
        {
            if (IsEmpty) return other;
            if (other.IsEmpty) return this;
            return new Rect(Math.Min(Left, other.Left), Math.Min(Top, other.Top),
                Math.Max(Right, other.Right), Math.Max(Bottom, other.Bottom));
        }

        public bool Equals(Rect r) => r != null && Left == r.Left && Top == r.Top && Right == r.Right && Bottom == r.Bottom;
        public override bool Equals(object o) => Equals(o as Rect);
        public override int GetHashCode() => HashCode.Combine(Left, Top, Right, Bottom);
        public override string ToString() => $"Rect({Left}, {Top}, {Right}, {Bottom})";
    }
}
