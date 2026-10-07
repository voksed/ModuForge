"""The parts of `functools` that scripts use."""


def reduce(function, iterable, *initial):
    iterator = iter(iterable)
    if initial:
        value = initial[0]
    else:
        try:
            value = next(iterator)
        except StopIteration:
            raise TypeError("reduce() of empty iterable with no initial value")
    for item in iterator:
        value = function(value, item)
    return value


class partial:
    def __init__(self, function, *args, **kwargs):
        self.func = function
        self.args = args
        self.keywords = kwargs

    def __call__(self, *args, **kwargs):
        merged = dict(self.keywords)
        merged.update(kwargs)
        return self.func(*(self.args + args), **merged)


def wraps(wrapped):
    def decorate(function):
        return function

    return decorate


def lru_cache(maxsize=128):
    def decorate(function):
        cache = {}

        def cached(*args):
            if args not in cache:
                cache[args] = function(*args)
            return cache[args]

        return cached

    if callable(maxsize):
        function = maxsize
        maxsize = 128
        return decorate(function)
    return decorate


cache = lru_cache(None)


def cmp_to_key(compare):
    class Key:
        def __init__(self, value):
            self.value = value

        def __lt__(self, other):
            return compare(self.value, other.value) < 0

        def __gt__(self, other):
            return compare(self.value, other.value) > 0

        def __eq__(self, other):
            return compare(self.value, other.value) == 0

    return Key
