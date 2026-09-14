package com.pyedit.app

object ExecutionProtocol {
    const val MSG_RUN = 1
    const val MSG_STDIN_LINE = 2
    const val MSG_STOP = 3

    const val MSG_STDOUT = 10
    const val MSG_STDERR = 11
    const val MSG_EXITED = 12
    const val MSG_REGISTER_CLIENT = 13
    // New: sent the instant the script's input() call actually blocks
    // waiting for a line — this is what drives "input box only active
    // when needed" and auto-showing the keyboard.
    const val MSG_INPUT_REQUESTED = 14

    const val KEY_SCRIPT_PATH = "script_path"
    const val KEY_TEXT = "text"
}