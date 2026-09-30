#!/usr/bin/env python3
"""Generates FuseIcons.kt from Lucide SVGs (ISC licence, see ui/designsystem/src/commonMain/composeResources/files/licenses/LICENSE-lucide.txt).

Usage: python3 tools/icons/generate_icons.py <lucide-static>/icons > ui/designsystem/.../FuseIcons.kt

Only the icons listed in ICONS are vendored, so the app ships what it uses. Every SVG element is turned
into plain path data at build time; the app parses it once, lazily, per icon.
"""
import re
import sys
import xml.etree.ElementTree as ET

# Kotlin name -> Lucide file name
ICONS = {
    "Home": "house", "Library": "library", "Grid": "layout-grid", "Gamepad": "gamepad-2", "Joystick": "joystick",
    "Chip": "cpu", "Globe": "globe", "Smartphone": "smartphone", "Download": "download",
    "CloudDownload": "cloud-download", "HardDrive": "hard-drive", "Settings": "settings", "Cog": "cog",
    "Sliders": "sliders-horizontal", "Search": "search", "Close": "x", "Check": "check",
    "ChevronLeft": "chevron-left", "ChevronRight": "chevron-right", "ChevronUp": "chevron-up",
    "ChevronDown": "chevron-down", "ArrowLeft": "arrow-left", "ArrowRight": "arrow-right", "Play": "play",
    "Star": "star", "Heart": "heart", "Bookmark": "bookmark", "Folder": "folder", "FolderOpen": "folder-open",
    "FolderSearch": "folder-search", "File": "file", "FileArchive": "file-archive", "Image": "image",
    "Images": "images", "Film": "film", "Clapperboard": "clapperboard", "Trophy": "trophy", "Award": "award",
    "Medal": "medal", "Clock": "clock", "Timer": "timer", "Hourglass": "hourglass", "Calendar": "calendar",
    "Wifi": "wifi", "WifiOff": "wifi-off", "Bluetooth": "bluetooth", "BluetoothOff": "bluetooth-off",
    "Sun": "sun", "Moon": "moon", "Volume": "volume-2", "VolumeOff": "volume-x", "Monitor": "monitor",
    "MonitorSmartphone": "monitor-smartphone", "DualScreen": "tablet-smartphone", "Power": "power",
    "CirclePower": "circle-power", "Refresh": "refresh-cw", "Undo": "undo-2", "RotateCcw": "rotate-ccw",
    "Trash": "trash-2", "Eye": "eye", "EyeOff": "eye-off", "Pencil": "pencil", "Type": "type",
    "TextCursor": "text-cursor-input", "Crop": "crop", "Move": "move", "MoveHorizontal": "move-horizontal",
    "Maximize": "maximize-2", "Palette": "palette", "Brush": "brush", "Sparkles": "sparkles",
    "Wand": "wand-sparkles", "Zap": "zap", "Bolt": "bolt", "Leaf": "leaf", "Gauge": "gauge", "Layers": "layers",
    "Grid3": "grid-3x3", "Grid2": "grid-2x2", "Rows": "rows-3", "Columns": "columns-3",
    "Carousel": "gallery-horizontal-end", "GalleryVertical": "gallery-vertical-end", "Square": "square",
    "RectVertical": "rectangle-vertical", "RectHorizontal": "rectangle-horizontal", "List": "list",
    "ListPlus": "list-plus", "Info": "info", "Warning": "triangle-alert", "Alert": "circle-alert",
    "CircleCheck": "circle-check", "CircleX": "circle-x", "CircleDot": "circle-dot", "Lock": "lock",
    "Key": "key-round", "Link": "link", "Link2": "link-2", "External": "external-link",
    "ArrowUpRight": "arrow-up-right", "Package": "package", "Box": "box", "Disc": "disc-3", "Save": "save",
    "Archive": "archive", "Tag": "tag", "Users": "users", "User": "user", "Plus": "plus", "Minus": "minus",
    "More": "ellipsis", "MoreVertical": "ellipsis-vertical", "Grip": "grip-vertical", "Keyboard": "keyboard",
    "Mouse": "mouse", "Hand": "hand", "Vibrate": "vibrate", "Music": "music", "Bell": "bell",
    "ShieldCheck": "shield-check", "Cloud": "cloud", "Server": "server", "Database": "database",
    "ScanSearch": "scan-search", "ScanLine": "scan-line", "BadgeCheck": "badge-check", "Cable": "cable",
    "Plug": "plug", "Unplug": "unplug", "Tv": "tv", "AppWindow": "app-window", "SquarePlay": "square-play",
    "CirclePlay": "circle-play", "Dashboard": "layout-dashboard", "PanelsTop": "panels-top-left",
    "HomePlus": "house-plus", "Pin": "pin", "PinOff": "pin-off", "History": "history", "Flame": "flame",
    "Activity": "activity", "Thermometer": "thermometer", "Memory": "memory-stick", "Battery": "battery",
    "BatteryCharging": "battery-charging", "BatteryLow": "battery-low", "Aperture": "aperture",
    "Swords": "swords", "Map": "map", "Compass": "compass", "Rocket": "rocket", "Target": "target",
    "Crosshair": "crosshair", "LogOut": "log-out", "Template": "layout-template", "Shapes": "shapes",
    "Contrast": "contrast", "Accessibility": "accessibility", "Chart": "chart-no-axes-column",
    "RefreshDot": "refresh-ccw-dot", "Import": "import", "Upload": "upload", "Share": "share-2",
    "QrCode": "qr-code", "Wrench": "wrench", "Blocks": "blocks", "ClipboardPaste": "clipboard-paste",
    "Filter": "list-filter", "Sort": "arrow-up-down", "FileQuestion": "file-question",
}

NUM = r"-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?"


def f(v):
    x = float(v)
    return str(int(x)) if x == int(x) else repr(round(x, 4))


def rect_path(e):
    x, y = float(e.get("x", 0)), float(e.get("y", 0))
    w, h = float(e.get("width")), float(e.get("height"))
    rx = float(e.get("rx", e.get("ry", 0)))
    ry = float(e.get("ry", rx))
    if rx <= 0:
        return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}Z"
    return (
        f"M{f(x + rx)} {f(y)}h{f(w - 2 * rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}"
        f"v{f(h - 2 * ry)}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w - 2 * rx))}"
        f"a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}v{f(-(h - 2 * ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}Z"
    )


def ellipse_path(cx, cy, rx, ry):
    return (
        f"M{f(cx - rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2 * rx)} 0"
        f"a{f(rx)} {f(ry)} 0 1 0 {f(-2 * rx)} 0Z"
    )


def points_path(points, close):
    nums = re.findall(NUM, points)
    pairs = [(nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2)]
    d = "M" + " L".join(f"{f(a)} {f(b)}" for a, b in pairs)
    return d + ("Z" if close else "")


def element_paths(root):
    out = []
    for e in root.iter():
        tag = e.tag.split("}")[-1]
        if tag == "path":
            out.append(e.get("d"))
        elif tag == "circle":
            out.append(ellipse_path(float(e.get("cx", 0)), float(e.get("cy", 0)), float(e.get("r")), float(e.get("r"))))
        elif tag == "ellipse":
            out.append(ellipse_path(float(e.get("cx", 0)), float(e.get("cy", 0)), float(e.get("rx")), float(e.get("ry"))))
        elif tag == "rect":
            out.append(rect_path(e))
        elif tag == "line":
            out.append(f"M{f(e.get('x1'))} {f(e.get('y1'))}L{f(e.get('x2'))} {f(e.get('y2'))}")
        elif tag == "polyline":
            out.append(points_path(e.get("points"), False))
        elif tag == "polygon":
            out.append(points_path(e.get("points"), True))
    return out


def main(icon_dir):
    print("// Generated by tools/icons/generate_icons.py from Lucide 1.49.0 (ISC). Do not edit by hand.")
    print("package io.github.matiyaaa.fuse.ui.designsystem.icons")
    print()
    print("import androidx.compose.ui.graphics.vector.ImageVector")
    print()
    print("/** Fuse's vendored icon set: Lucide line icons, 24 x 24, drawn with round caps and joins. */")
    print("object FuseIcons {")
    for name in sorted(ICONS):
        root = ET.parse(f"{icon_dir}/{ICONS[name]}.svg").getroot()
        paths = ", ".join('"' + p.replace('"', '\\"') + '"' for p in element_paths(root))
        print(f"    val {name}: ImageVector by lazy {{ lineIcon(\"{name}\", {paths}) }}")
    print("}")


if __name__ == "__main__":
    main(sys.argv[1])
