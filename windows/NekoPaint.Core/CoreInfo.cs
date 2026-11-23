// SPDX-License-Identifier: GPL-3.0-or-later
namespace NekoPaint.Core
{
    public static class CoreInfo
    {
        /// <summary>True when nekocore.dll (the C drawing core) is loaded.</summary>
        public static bool NativeAvailable => NativeStroke.Available;
    }
}
