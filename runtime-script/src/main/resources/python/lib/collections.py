"""The parts of `collections` that scripts use: defaultdict, OrderedDict, deque, Counter, namedtuple."""
from _native import defaultdict

OrderedDict = dict


class deque:
    def __init__(self, iterable=(), maxlen=None):
        self.maxlen = maxlen
        self._items = list(iterable)
        self._trim_left()

    def _trim_left(self):
        if self.maxlen is not None:
            while len(self._items) > self.maxlen:
                self._items.pop(0)

    def _trim_right(self):
        if self.maxlen is not None:
            while len(self._items) > self.maxlen:
                self._items.pop()

    def append(self, item):
        self._items.append(item)
        self._trim_left()

    def appendleft(self, item):
        self._items.insert(0, item)
        self._trim_right()

    def extend(self, iterable):
        for item in iterable:
            self.append(item)

    def extendleft(self, iterable):
        for item in iterable:
            self.appendleft(item)

    def pop(self):
        if not self._items:
            raise IndexError("pop from an empty deque")
        return self._items.pop()

    def popleft(self):
        if not self._items:
            raise IndexError("pop from an empty deque")
        return self._items.pop(0)

    def clear(self):
        self._items.clear()

    def rotate(self, n=1):
        if self._items:
            n = n % len(self._items)
            self._items[:] = self._items[-n:] + self._items[:-n] if n else self._items

    def __len__(self):
        return len(self._items)

    def __iter__(self):
        return iter(list(self._items))

    def __getitem__(self, index):
        return self._items[index]

    def __setitem__(self, index, value):
        self._items[index] = value

    def __contains__(self, item):
        return item in self._items

    def __bool__(self):
        return bool(self._items)

    def __repr__(self):
        return "deque(" + repr(self._items) + ")"


class Counter:
    def __init__(self, iterable=None, **kwargs):
        self._counts = {}
        self.update(iterable, **kwargs)

    def update(self, iterable=None, **kwargs):
        if iterable is not None:
            if isinstance(iterable, (dict, Counter)):
                for key, count in iterable.items():
                    self._counts[key] = self._counts.get(key, 0) + count
            else:
                for item in iterable:
                    self._counts[item] = self._counts.get(item, 0) + 1
        for key, count in kwargs.items():
            self._counts[key] = self._counts.get(key, 0) + count

    def most_common(self, n=None):
        ordered = sorted(self._counts.items(), key=lambda pair: pair[1], reverse=True)
        return ordered if n is None else ordered[:n]

    def elements(self):
        for key, count in self._counts.items():
            for _ in range(count):
                yield key

    def total(self):
        return sum(self._counts.values())

    def get(self, key, default=None):
        return self._counts.get(key, default)

    def keys(self):
        return self._counts.keys()

    def values(self):
        return self._counts.values()

    def items(self):
        return self._counts.items()

    def __getitem__(self, key):
        return self._counts.get(key, 0)

    def __setitem__(self, key, value):
        self._counts[key] = value

    def __delitem__(self, key):
        del self._counts[key]

    def __contains__(self, key):
        return key in self._counts

    def __iter__(self):
        return iter(list(self._counts))

    def __len__(self):
        return len(self._counts)

    def __repr__(self):
        return "Counter(" + repr(dict(self.most_common())) + ")"


def namedtuple(typename, field_names):
    if isinstance(field_names, str):
        field_names = field_names.replace(",", " ").split()
    fields = tuple(field_names)

    class Tuple:
        _fields = fields

        def __init__(self, *args, **kwargs):
            if len(args) > len(fields):
                raise TypeError(typename + "() takes " + str(len(fields)) + " positional arguments but " + str(len(args)) + " were given")
            values = dict(zip(fields, args))
            for name, value in kwargs.items():
                if name not in fields:
                    raise TypeError(typename + "() got an unexpected keyword argument '" + name + "'")
                values[name] = value
            for name in fields:
                if name not in values:
                    raise TypeError(typename + "() missing required argument: '" + name + "'")
                setattr(self, name, values[name])

        def __iter__(self):
            return iter([getattr(self, name) for name in fields])

        def __len__(self):
            return len(fields)

        def __getitem__(self, index):
            return [getattr(self, name) for name in fields][index]

        def __eq__(self, other):
            return tuple(self) == tuple(other)

        def __hash__(self):
            return hash(tuple(self))

        def __repr__(self):
            return typename + "(" + ", ".join(name + "=" + repr(getattr(self, name)) for name in fields) + ")"

        def _asdict(self):
            return {name: getattr(self, name) for name in fields}

        def _replace(self, **changes):
            values = self._asdict()
            values.update(changes)
            return Tuple(**values)

    return Tuple
