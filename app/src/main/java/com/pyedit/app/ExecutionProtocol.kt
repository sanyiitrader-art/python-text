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
    // New: structured error info reported directly by Python (line/type/
    // message), rather than parsed from raw traceback text on this side.
    const val MSG_ERROR = 15

    const val KEY_SCRIPT_PATH = "script_path"
    const val KEY_TEXT = "text"
    const val KEY_ERROR_LINE = "error_line"
    const val KEY_ERROR_TYPE = "error_type"
    const val KEY_ERROR_MESSAGE = "error_message"
}