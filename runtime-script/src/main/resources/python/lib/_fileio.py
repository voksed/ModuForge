"""open() for text and binary files, kept in mf.storage (the module's own sandboxed files)."""
import mf


class _File:
    def __init__(self, path, mode, binary, content):
        self.name = path
        self.mode = mode
        self.closed = False
        self._binary = binary
        self._writable = mode[0] in "wax" or "+" in mode
        self._readable = mode[0] == "r" or "+" in mode
        self._append = mode[0] == "a"
        self._dirty = mode[0] in "wx"
        self._data = content
        self._pos = len(content) if self._append else 0

    def _check(self):
        if self.closed:
            raise ValueError("I/O operation on closed file.")

    def read(self, size=-1):
        self._check()
        if not self._readable:
            raise OSError("not readable")
        end = len(self._data) if size is None or size < 0 else self._pos + size
        chunk = self._data[self._pos:end]
        self._pos += len(chunk)
        return chunk

    def readline(self):
        self._check()
        if not self._readable:
            raise OSError("not readable")
        newline = b"\n" if self._binary else "\n"
        end = self._data.find(newline, self._pos)
        end = len(self._data) if end < 0 else end + 1
        line = self._data[self._pos:end]
        self._pos = end
        return line

    def readlines(self):
        lines = []
        while True:
            line = self.readline()
            if not line:
                return lines
            lines.append(line)

    def __iter__(self):
        return iter(self.readlines())

    def write(self, data):
        self._check()
        if not self._writable:
            raise OSError("not writable")
        if self._binary != isinstance(data, bytes):
            raise TypeError("write() argument must be " + ("bytes" if self._binary else "str"))
        if self._append:
            self._pos = len(self._data)
        self._data = self._data[:self._pos] + data + self._data[self._pos + len(data):]
        self._pos += len(data)
        self._dirty = True
        return len(data)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def seek(self, offset, whence=0):
        self._check()
        base = 0 if whence == 0 else (self._pos if whence == 1 else len(self._data))
        self._pos = max(0, base + offset)
        return self._pos

    def tell(self):
        return self._pos

    def flush(self):
        if self._dirty and not self.closed:
            mf.storage.write(self.name, self._data)
            self._dirty = False

    def close(self):
        if not self.closed:
            self.flush()
            self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def open(file, mode="r", buffering=-1, encoding=None, errors=None, newline=None):
    path = str(file)
    kind = mode.replace("b", "").replace("t", "").replace("+", "")
    if kind not in ("r", "w", "a", "x"):
        raise ValueError("invalid mode: '" + mode + "'")
    binary = "b" in mode
    try:
        existing = mf.storage.read_bytes(path) if binary else mf.storage.read(path)
    except mf.Error as error:
        raise OSError(str(error))
    if kind == "r" and existing is None:
        raise FileNotFoundError("[Errno 2] No such file or directory: '" + path + "'")
    if kind == "x" and existing is not None:
        raise OSError("[Errno 17] File exists: '" + path + "'")
    if kind in ("w", "x") or existing is None:
        existing = b"" if binary else ""
    return _File(path, mode, binary, existing)
