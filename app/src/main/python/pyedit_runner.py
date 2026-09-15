"""
Runs inside the :pyexec process via Chaquopy. Executes the user's script
with stdout/stderr/stdin redirected to Kotlin-backed objects, and reports
structured error info (line, type, message) to a dedicated error_reporter
object rather than leaving the Kotlin side to parse raw traceback text.
"""
import sys
import io
import traceback


class _WriterStream(io.TextIOBase):
    def __init__(self, writer):
        self._writer = writer

    def write(self, s):
        self._writer.write(s)
        return len(s)

    def flush(self):
        pass

    def writable(self):
        return True


class _ReaderStream(io.TextIOBase):
    def __init__(self, reader):
        self._reader = reader

    def readline(self, size=-1):
        line = self._reader.readLine()
        if line is None:
            return ""
        return line + "\n"

    def readable(self):
        return True


def run_script(path, stdout_writer, stderr_writer, stdin_reader, error_reporter):
    sys.stdout = _WriterStream(stdout_writer)
    sys.stderr = _WriterStream(stderr_writer)
    sys.stdin = _ReaderStream(stdin_reader)

    try:
        with open(path, "r", encoding="utf-8") as f:
            source = f.read()
        code = compile(source, path, "exec")
        exec(code, {"__name__": "__main__"})
    except SyntaxError as e:
        traceback.print_exc()
        line = e.lineno or 0
        msg = str(e.msg) if e.msg else str(e)
        error_reporter.report(line, type(e).__name__, msg)
    except SystemExit:
        pass
    except BaseException as e:
        traceback.print_exc()
        last_line = 0
        for frame in traceback.extract_tb(e.__traceback__):
            if frame.filename == path:
                last_line = frame.lineno
        error_reporter.report(last_line, type(e).__name__, str(e))


def check_syntax(source_text, display_path):
    """
    Spec's "Compile" (§71): syntax/compilation checking only, no
    execution. Returns (True, None) on success or (False, error_info) on
    a SyntaxError, WITHOUT running any of the script's code.
    """
    try:
        compile(source_text, display_path, "exec")
        return True, None
    except SyntaxError as e:
        line = e.lineno or 0
        msg = str(e.msg) if e.msg else str(e)
        return False, (line, type(e).__name__, msg)