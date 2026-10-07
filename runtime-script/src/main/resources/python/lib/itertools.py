"""The parts of `itertools` that scripts use."""


def chain(*iterables):
    for iterable in iterables:
        for item in iterable:
            yield item


def count(start=0, step=1):
    value = start
    while True:
        yield value
        value += step


def repeat(value, times=None):
    if times is None:
        while True:
            yield value
    else:
        for _ in range(times):
            yield value


def cycle(iterable):
    saved = list(iterable)
    while saved:
        for item in saved:
            yield item


def islice(iterable, *args):
    start, stop, step = 0, None, 1
    if len(args) == 1:
        stop = args[0]
    elif len(args) >= 2:
        start, stop = args[0], args[1]
        if len(args) > 2:
            step = args[2]
    start = start or 0
    index = 0
    next_index = start
    for item in iterable:
        if stop is not None and index >= stop:
            return
        if index == next_index:
            yield item
            next_index += step
        index += 1


def accumulate(iterable, function=None):
    iterator = iter(iterable)
    try:
        total = next(iterator)
    except StopIteration:
        return
    yield total
    for item in iterator:
        total = function(total, item) if function else total + item
        yield total


def takewhile(predicate, iterable):
    for item in iterable:
        if not predicate(item):
            return
        yield item


def dropwhile(predicate, iterable):
    dropping = True
    for item in iterable:
        if dropping and predicate(item):
            continue
        dropping = False
        yield item


def starmap(function, iterable):
    for args in iterable:
        yield function(*args)


def zip_longest(*iterables, fillvalue=None):
    lists = [list(iterable) for iterable in iterables]
    longest = max([len(items) for items in lists]) if lists else 0
    for i in range(longest):
        yield tuple(items[i] if i < len(items) else fillvalue for items in lists)


def product(*iterables, repeat=1):
    pools = [list(pool) for pool in iterables] * repeat
    result = [[]]
    for pool in pools:
        result = [existing + [item] for existing in result for item in pool]
    for combination in result:
        yield tuple(combination)


def permutations(iterable, r=None):
    pool = list(iterable)
    r = len(pool) if r is None else r

    def build(chosen, remaining):
        if len(chosen) == r:
            yield tuple(chosen)
            return
        for i in range(len(remaining)):
            for result in build(chosen + [remaining[i]], remaining[:i] + remaining[i + 1:]):
                yield result

    for result in build([], pool):
        yield result


def combinations(iterable, r):
    pool = list(iterable)

    def build(start, chosen):
        if len(chosen) == r:
            yield tuple(chosen)
            return
        for i in range(start, len(pool)):
            for result in build(i + 1, chosen + [pool[i]]):
                yield result

    for result in build(0, []):
        yield result


def groupby(iterable, key=None):
    group = []
    current = None
    started = False
    for item in iterable:
        k = key(item) if key else item
        if started and k != current:
            yield current, iter(group)
            group = []
        current = k
        started = True
        group.append(item)
    if started:
        yield current, iter(group)
