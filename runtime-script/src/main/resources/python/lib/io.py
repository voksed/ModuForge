"""BytesIO and StringIO: in-memory files."""


class BytesIO:
    def __init__(self, initial=b""):
        self._data = bytes(initial)
        self._pos = 0

    def read(self, size=-1):
        end = len(self._data) if size is None or size < 0 else self._pos + size
        chunk = self._data[self._pos:end]
        self._pos += len(chunk)
        return chunk

    def write(self, data):
        data = bytes(data)
        self._data = self._data[:self._pos] + data + self._data[self._pos + len(data):]
        self._pos += len(data)
        return len(data)

    def getvalue(self):
        return self._data

    def seek(self, offset, whence=0):
        base = 0 if whence == 0 else (self._pos if whence == 1 else len(self._data))
        self._pos = max(0, base + offset)
        return self._pos

    def tell(self):
        return self._pos

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class StringIO:
    def __init__(self, initial=""):
        self._data = initial
        self._pos = 0

    def read(self, size=-1):
        end = len(self._data) if size is None or size < 0 else self._pos + size
        chunk = self._data[self._pos:end]
        self._pos += len(chunk)
        return chunk

    def readline(self):
        end = self._data.find("\n", self._pos)
        end = len(self._data) if end < 0 else end + 1
        line = self._data[self._pos:end]
        self._pos = end
        return line

    def write(self, text):
        self._data = self._data[:self._pos] + text + self._data[self._pos + len(text):]
        self._pos += len(text)
        return len(text)

    def getvalue(self):
        return self._data

    def seek(self, offset, whence=0):
        base = 0 if whence == 0 else (self._pos if whence == 1 else len(self._data))
        self._pos = max(0, base + offset)
        return self._pos

    def tell(self):
        return self._pos

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False
