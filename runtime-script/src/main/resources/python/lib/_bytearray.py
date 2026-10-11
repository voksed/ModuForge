"""A mutable byte string. Slower than bytes; use it for buffers that are built up piece by piece."""


class bytearray:
    def __init__(self, source=None):
        if source is None:
            self._items = []
        elif isinstance(source, int):
            self._items = [0] * source
        elif isinstance(source, str):
            self._items = list(source.encode("utf-8"))
        else:
            self._items = list(source)

    def __len__(self):
        return len(self._items)

    def __iter__(self):
        return iter(self._items)

    def __getitem__(self, index):
        if isinstance(index, slice):
            return bytearray(self._items[index])
        return self._items[index]

    def __setitem__(self, index, value):
        if isinstance(index, slice):
            self._items[index] = list(value)
        else:
            self._items[index] = value

    def __add__(self, other):
        return bytearray(self._items + list(other))

    def __iadd__(self, other):
        self._items.extend(list(other))
        return self

    def __eq__(self, other):
        return self._items == list(other)

    def __bytes__(self):
        return bytes(self._items)

    def __repr__(self):
        return "bytearray(" + repr(bytes(self._items)) + ")"

    def append(self, value):
        self._items.append(value)

    def extend(self, values):
        self._items.extend(list(values))

    def pop(self, index=-1):
        return self._items.pop(index)

    def clear(self):
        self._items = []

    def hex(self):
        return bytes(self._items).hex()

    def decode(self, encoding="utf-8"):
        return bytes(self._items).decode(encoding)
