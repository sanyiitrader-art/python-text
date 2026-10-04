package com.pyedit.app

object ExecutionProtocol {
    const val MSG_RUN = 1
    const val MSG_STDIN_LINE = 2
    const val MSG_STOP = 3

    const val MSG_STDOUT = 10
    const val MSG_STDERR = 11
    const val MSG_EXITED = 12
    const val MSG_REGISTER_CLIENT = 13
    const val MSG_INPUT_REQUESTED = 14
    const val MSG_ERROR = 15
    // New: sent back immediately in response to MSG_REGISTER_CLIENT, so a
    // freshly (re)launched UI can learn whether a script is still running
    // in the background service from a previous session.
    const val MSG_STATUS_RESPONSE = 16

    const val KEY_SCRIPT_PATH = "script_path"
    const val KEY_TEXT = "text"
    const val KEY_ERROR_LINE = "error_line"
    const val KEY_ERROR_TYPE = "error_type"
    const val KEY_ERROR_MESSAGE = "error_message"
}