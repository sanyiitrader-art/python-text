"""
Runs inside the :pyexec process via Chaquopy. Executes the user's script
with stdout/stderr/stdin redirected to Kotlin-backed objects passed in
from the Java/Kotlin side, so output streams back across the process
boundary and input() can read from the UI.
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


def run_script(path, stdout_writer, stderr_writer, stdin_reader):
    sys.stdout = _WriterStream(stdout_writer)
    sys.stderr = _WriterStream(stderr_writer)
    sys.stdin = _ReaderStream(stdin_reader)

    try:
        with open(path, "r", encoding="utf-8") as f:
            source = f.read()
        code = compile(source, path, "exec")
        exec(code, {"__name__": "__main__"})
    except SystemExit:
        pass
    except BaseException:
        traceback.print_exc()