#!/usr/bin/env python3
"""Extract the production network-warning logic into a real QML test window."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("discover_window", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
source = args.discover_window.read_text()
logic = source[source.index("    function updateConnectivityWarning()"):
               source.index("    // This property is queried from C++")]
args.output.write_text('''import QtQuick
import QtQuick.Window
import QtQuick.Controls as QQC2
import TestNetwork 1.0
Window {
    id: window
    width: 640; height: 480; visible: true
    Component.onCompleted: updateConnectivityWarning()
    QQC2.Popup {
        id: connectionDialog
        objectName: "connectionDialog"
        width: 200; height: 100
        closePolicy: QQC2.Popup.NoAutoClose
        enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 1 } }
        exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 300 } }
    }
''' + logic + '\n}\n')
