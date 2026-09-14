package com.pyedit.app

/**
 * Shared Messenger message "what" codes and Bundle keys between
 * MainActivity (UI process) and PythonExecutionService (:pyexec process).
 * Kept in one object so both sides can never drift out of sync on the
 * numeric codes.
 */
object ExecutionProtocol {
    // Client -> Service
    const val MSG_RUN = 1
    const val MSG_STDIN_LINE = 2
    const val MSG_STOP = 3
    // Note: MSG_STOP is handled by the client killing the service's
    // process directly rather than sending a graceful message — see
    // MainActivity's stop handling. Kept here for documentation/clarity.

    // Service -> Client
    const val MSG_STDOUT = 10
    const val MSG_STDERR = 11
    const val MSG_EXITED = 12
    const val MSG_REGISTER_CLIENT = 13

    const val KEY_SCRIPT_PATH = "script_path"
    const val KEY_TEXT = "text"
}