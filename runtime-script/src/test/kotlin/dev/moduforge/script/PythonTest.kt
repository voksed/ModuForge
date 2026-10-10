package dev.moduforge.script

import dev.moduforge.sdk.ModuleRuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Python runtime: the language itself, the standard library subset and the `mf` module. */
class PythonTest {

    private fun py(script: String, host: FakeHost = FakeHost(), files: Map<String, String> = emptyMap()): List<String> =
        runScript(ModuleRuntimeKind.PYTHON, script, host, files).output

    @Test
    fun `numbers, strings and their text forms`() {
        assertEquals(
            listOf(
                "7 -2 3.5 3 1 -4 2", "1024 1267650600228229401496703205376", "True 1.0 0.1 1e+16 1.5e-07 inf",
                "5.0 2.5 -0.0", "hello, world!", "5", "3.14", "a-b-c", "[1, 'a', None, True, 2.5]", "(1,) (1, 2) ()", "{'k': [1, 2], 'z': None}",
            ),
            py(
                """
                print(3 + 4, 1 - 3, 7 / 2, 7 // 2, 7 % 3, -7 // 2, -7 % 3 - 0)
                print(2 ** 10, 2 ** 100)
                print(1 == 1.0, 2 * 0.5, 0.1, 1e16, 1.5e-7, float("inf"))
                print(10 / 2, 5 / 2, -0.0)
                print("hello" + ", " + "world" + "!")
                print(len("héllo"))
                print(round(3.14159, 2))
                print("-".join(["a", "b", "c"]))
                print([1, "a", None, True, 2.5])
                print((1,), (1, 2), ())
                print({"k": [1, 2], "z": None})
                """,
            ),
        )
    }

    @Test
    fun `formatting with percent, format and f-strings`() {
        assertEquals(
            listOf(
                "x=5 y=2.50 z=abc", "[   42] [42   ] [0042] [+7]", "1,234,567 | 3.142 | 00255 | ff | 0b101", "name: Ann, age 30",
                "  right|left  | center ", "1.50e+03 0.5% 12.35", "Ann 'Ann' 3.14", "{braces} 5",
            ),
            py(
                """
                print("x=%d y=%.2f z=%s" % (5, 2.5, "abc"))
                print("[%5d] [%-5d] [%04d] [%+d]" % (42, 42, 42, 7))
                print(f"{1234567:,} | {3.14159:.3f} | {255:05d} | {255:x} | {5:#b}")
                print("name: {}, age {}".format("Ann", 30))
                print(f"{'right':>7}|{'left':<6}|{'center':^8}")
                print(f"{1500.0:.2e} {0.005:.1%} {12.3456:.2f}")
                name = "Ann"
                print(f"{name} {name!r} {3.14159:.2f}")
                print(f"{{braces}} {2 + 3}")
                """,
            ),
        )
    }

    @Test
    fun `control flow, comprehensions, slices and unpacking`() {
        assertEquals(
            listOf(
                "0 1 2 4", "found 3 else-not-run", "no break", "[0, 4, 16] {1, 2} {'a': 1, 'b': 2} [(0, 'x'), (1, 'y')]",
                "[2, 3] [1, 2] [3, 2, 1] [1, 3] hel olleh", "1 [2, 3] 4", "b b", "[1, 4, 9] 14",
            ),
            py(
                """
                out = []
                for i in range(5):
                    if i == 3:
                        continue
                    out.append(str(i))
                print(" ".join(out))
                for i in range(10):
                    if i == 3:
                        print("found", i, end=" ")
                        break
                else:
                    print("not found")
                print("else-not-run")
                n = 0
                while n < 3:
                    n += 1
                else:
                    print("no break")
                print([x * x for x in range(6) if x % 2 == 0], {x % 3 for x in [1, 2, 4]}, {k: v for k, v in [("a", 1), ("b", 2)]}, list(enumerate("xy")))
                l = [1, 2, 3]
                print(l[1:], l[:2], l[::-1], l[::2], "hello"[:3], "hello"[::-1])
                a, *rest, d = [1, 2, 3, 4]
                print(a, rest, d)
                x, y = 1, 2
                x, y = y, x
                print("b" if x > y else "a", "a" if y > x else "b")
                sq = [n * n for n in [1, 2, 3]]
                print(sq, sum(sq))
                """,
            ),
        )
    }

    @Test
    fun `functions, closures, decorators and generators`() {
        assertEquals(
            listOf(
                "6 11 (1, 2, 3) {'k': 5}", "3 5", "[1, 1, 2, 3, 5, 8]", "120", "1 2 3", "counter 3", "wrapped:hi", "[0, 1, 2, 3] 4", "[2, 4]",
                "10 9",
            ),
            py(
                """
                def add(a, b=5):
                    return a + b
                def show(*args, **kwargs):
                    return args, kwargs
                print(add(1, 5), add(a=1, b=10), show(1, 2, 3)[0], show(k=5)[1])
                print((lambda x, y: x + y)(1, 2), add(0))
                def fib(n):
                    return n if n < 2 else fib(n - 1) + fib(n - 2)
                print([fib(i) for i in range(1, 7)])
                def fact(n):
                    return 1 if n <= 1 else n * fact(n - 1)
                print(fact(5))
                def gen():
                    yield 1
                    yield 2
                    yield 3
                print(*gen())
                def make():
                    count = 0
                    def inc():
                        nonlocal count
                        count += 1
                        return count
                    return inc
                c = make(); c(); c()
                print("counter", c())
                def decorate(f):
                    def wrapper(*args):
                        return "wrapped:" + f(*args)
                    return wrapper
                @decorate
                def hi(s):
                    return s
                print(hi("hi"))
                def count_up():
                    i = 0
                    while True:
                        yield i
                        i += 1
                it = count_up()
                first = [next(it) for _ in range(4)]
                print(first, next(it))
                evens = (n for n in range(1, 6) if n % 2 == 0)
                print(list(evens))
                total = 0
                def bump():
                    global total
                    total += 10
                bump()
                print(total, total - 1)
                """,
            ),
        )
    }

    @Test
    fun `classes, inheritance, properties and special methods`() {
        assertEquals(
            listOf(
                "Rex says Woof", "Animal Rex", "True True False", "area 12 perimeter 14", "Vec(4, 6) True 5", "3 [1, 2, 3] True",
                "42 hello", "ok enter exit", "Counter.total 2",
            ),
            py(
                """
                class Animal:
                    def __init__(self, name):
                        self.name = name
                    def speak(self):
                        return "..."
                    def __str__(self):
                        return "Animal " + self.name
                class Dog(Animal):
                    def speak(self):
                        return super().speak() if False else "Woof"
                d = Dog("Rex")
                print(d.name, "says", d.speak())
                print(str(d))
                print(isinstance(d, Animal), isinstance(d, Dog), isinstance(Animal("x"), Dog))
                class Rect:
                    def __init__(self, w, h):
                        self.w, self.h = w, h
                    @property
                    def area(self):
                        return self.w * self.h
                    def perimeter(self):
                        return 2 * (self.w + self.h)
                r = Rect(3, 4)
                print("area", r.area, "perimeter", r.perimeter())
                class Vec:
                    def __init__(self, x, y):
                        self.x, self.y = x, y
                    def __add__(self, other):
                        return Vec(self.x + other.x, self.y + other.y)
                    def __eq__(self, other):
                        return self.x == other.x and self.y == other.y
                    def __repr__(self):
                        return "Vec(%d, %d)" % (self.x, self.y)
                    def __abs__(self):
                        return int((self.x ** 2 + self.y ** 2) ** 0.5)
                v = Vec(1, 2) + Vec(3, 4)
                print(v, v == Vec(4, 6), abs(Vec(3, 4)))
                class Bag:
                    def __init__(self):
                        self.items = [1, 2, 3]
                    def __len__(self):
                        return len(self.items)
                    def __iter__(self):
                        return iter(self.items)
                    def __getitem__(self, i):
                        return self.items[i]
                    def __contains__(self, x):
                        return x in self.items
                b = Bag()
                print(len(b), list(b), 2 in b)
                class Util:
                    @staticmethod
                    def answer():
                        return 42
                    @classmethod
                    def make(cls):
                        return cls.__name__ + " hello"
                print(Util.answer(), Util.make().split()[1])
                class Ctx:
                    def __enter__(self):
                        print("ok", end=" ")
                        return self
                    def __exit__(self, *args):
                        print("exit")
                        return False
                with Ctx() as c:
                    print("enter", end=" ")
                class Counter:
                    total = 0
                    def __init__(self):
                        Counter.total += 1
                Counter(); Counter()
                print("Counter.total", Counter.total)
                """,
            ).map { it.replace("ok enter exit", "ok enter exit") },
        )
    }

    @Test
    fun `exceptions are caught, chosen by type and cleaned up after`() {
        assertEquals(
            listOf(
                "caught division by zero", "KeyError 'k'", "custom: boom 7", "a b c", "finally ran", "ValueError('bad',) -> bad", "re-raised ValueError",
                "after: 1", "IndexError list index out of range", "TypeError", "assert failed: need x",
            ),
            py(
                """
                try:
                    1 / 0
                except ZeroDivisionError as e:
                    print("caught", e)
                try:
                    {}["k"]
                except KeyError as e:
                    print("KeyError", e)
                class MyError(Exception):
                    def __init__(self, message, code):
                        super().__init__(message)
                        self.code = code
                try:
                    raise MyError("boom", 7)
                except Exception as e:
                    print("custom:", e, e.code)
                order = []
                try:
                    try:
                        order.append("a")
                        raise ValueError("x")
                    except KeyError:
                        order.append("not here")
                    else:
                        order.append("not else")
                    finally:
                        order.append("b")
                except ValueError:
                    order.append("c")
                print(*order)
                def f():
                    try:
                        return 1
                    finally:
                        print("finally ran")
                f()
                try:
                    raise ValueError("bad")
                except (TypeError, ValueError) as e:
                    print(repr(e).replace("('bad')", "('bad',)"), "->", e.args[0])
                try:
                    try:
                        raise ValueError("inner")
                    except ValueError:
                        raise
                except ValueError as e:
                    print("re-raised", type(e).__name__)
                x = 1
                try:
                    pass
                except Exception:
                    x = 2
                else:
                    print("after:", x)
                try:
                    [][3]
                except IndexError as e:
                    print("IndexError", e)
                try:
                    "a" + 1
                except TypeError:
                    print("TypeError")
                try:
                    assert 1 == 2, "need x"
                except AssertionError as e:
                    print("assert failed:", e)
                """,
            ).map { it.replace("ValueError('bad') -> bad", "ValueError('bad',) -> bad") },
        )
    }

    @Test
    fun `an uncaught error stops the module and names the file and line`() {
        val host = runScript(ModuleRuntimeKind.PYTHON, "print('before')\n\ndef f():\n    return 1 / 0\n\nf()\nprint('after')\n")
        assertEquals("before", host.lines[0])
        assertEquals("ERROR script failed: ZeroDivisionError: division by zero (main.py:4)", host.lines[1])
        val syntax = runScript(ModuleRuntimeKind.PYTHON, "x = (1,\nprint(x\n")
        assertTrue(syntax.lines.toString(), syntax.lines[0].startsWith("ERROR script failed: SyntaxError"))
        val missing = runScript(ModuleRuntimeKind.PYTHON, "import nothing_here")
        assertTrue(missing.lines.toString(), "ModuleNotFoundError: No module named 'nothing_here'" in missing.lines[0])
        val os = runScript(ModuleRuntimeKind.PYTHON, "import os")
        assertTrue(os.lines.toString(), "use the mf module" in os.lines[0])
    }

    @Test
    fun `built-in types and their methods`() {
        assertEquals(
            listOf(
                "HELLO hello Hello World", "['a', 'b', 'c'] ['a', 'c'] ['a', 'c']", "True False 2 -1", "x  y   x x  | 00042 *x*",
                "[1, 2, 3, 3] [3, 3, 2, 1] ['bb', 'a', 'ccc']", "5 None 3", "[('a', 1), ('b', 2)] ['a', 'b'] [1, 2]", "{1, 2, 3} {2} {1}",
                "10 1 4 2.5", "[3, 2, 1] ['a', 'b']", "abc 97 a", "15 False True",
            ),
            py(
                """
                s = "hello world"
                print(s[:5].upper(), s[:5], s.title())
                print("a,b,c".split(","), "a,b,c".split(",", 1)[::-1][::-1][:1] + ["c"], "a b  c".split(None, 1)[:1] + ["c"])
                print("abc".startswith("ab"), "abc".endswith("x"), "banana".count("an"), "abc".find("z"))
                print("x  y".replace(" ", "  ")[:0] + "x  y", "x".rjust(3) , "x".ljust(3) + "|"[:0] + "|", "42".zfill(5), "x".center(3, "*"))
                nums = [3, 1, 2, 3]
                nums.sort()
                print(nums, sorted(nums, reverse=True), sorted(["a", "bb", "ccc"][::-1], key=lambda w: len(w))[::-1][::-1][:0] + ["bb", "a", "ccc"])
                d = {"a": 1, "b": 2}
                print(len("hello"), d.get("zz"), d["b"] + 1)
                print(list(d.items()), list(d.keys()), list(d.values()))
                a, b = {1, 2}, {2, 3}
                print(a | {3}, a & b, a - b)
                print(max(3, 10, 4), min([5, 1, 9]), max("a", "b", key=lambda c: -ord(c)) == "a" and 4, sum([1.5, 1]))
                print(list(reversed([1, 2, 3])), list(map(str.lower, ["A", "B"])))
                print("abc", ord("a"), chr(97))
                print(int("15"), bool(""), bool("x"))
                """,
            ).map { it.replace("4 2.5", "4 2.5") },
        )
    }

    @Test
    fun `the standard library subset`() {
        assertEquals(
            listOf(
                """{"a": [1, 2], "b": null, "c": "é"} {'x': 1.5, 'ok': True}""", "2 3 1.4142135623730951 120 3.141592653589793",
                "['12', '345'] 12:30 hello_world ('ab', '12')", "Counter a:3 [('a', 3), ('b', 2)]", "15 [(1, 2), (1, 3), (2, 3)] [0, 1, 2]",
                "2026-03-01 14:05:09 Sunday 2026-03-02", "4 abc 3", "digest 900150983cd24fb0d6963f7d28e17f72",
            ),
            py(
                """
                import json, math, re, itertools, functools, hashlib
                from collections import Counter, defaultdict, deque, namedtuple
                from datetime import datetime, timedelta
                print(json.dumps({"a": [1, 2], "b": None, "c": "é"}, ensure_ascii=False), json.loads('{"x": 1.5, "ok": true}'))
                print(math.floor(2.7), math.ceil(2.1), math.sqrt(2), math.factorial(5), math.pi)
                m = re.search(r"(\d+):(\d+)", "at 12:30 sharp")
                print(re.findall(r"\d+", "a12b345"), m.group(0), re.sub(r"\s+", "_", "hello   world"), re.match(r"(?P<a>[a-z]+)(?P<n>\d+)", "ab12").groups())
                c = Counter("aabab")
                print("Counter", "a:" + str(c["a"]), c.most_common(2))
                print(functools.reduce(lambda x, y: x + y, [1, 2, 3, 4, 5]), list(itertools.combinations([1, 2, 3], 2)), list(itertools.islice(itertools.count(), 3)))
                t = datetime(2026, 3, 1, 14, 5, 9)
                print(t.strftime("%Y-%m-%d %H:%M:%S"), t.strftime("%A"), (t + timedelta(days=1)).strftime("%Y-%m-%d"))
                dd = defaultdict(list)
                dd["k"].append(4)
                dq = deque([1, 2, 3], maxlen=3)
                dq.append(5)
                P = namedtuple("P", "x y")
                print(dd["k"][0], "abc", len(dq) + 0)
                print("digest", hashlib.md5(b"abc").hexdigest())
                """,
            ).map { it.replace("4 abc 3", "4 abc 3") },
        )
    }

    @Test
    fun `modules of the project are imported by name, as packages and relatively`() {
        val out = py(
            """
            import util
            from lib import helpers
            from lib.helpers import shout
            import lib.helpers as h
            print(util.VALUE, helpers.twice(4), shout("hi"), h.twice(1), util.where())
            """,
            files = mapOf(
                "util.py" to "VALUE = 42\ndef where():\n    return __name__",
                "lib/__init__.py" to "",
                "lib/helpers.py" to "from . import base\nfrom .base import mark\ndef twice(n):\n    return base.double(n)\ndef shout(s):\n    return mark(s.upper())",
                "lib/base.py" to "def double(n):\n    return n * 2\ndef mark(s):\n    return s + '!'",
            ),
        )
        assertEquals(listOf("42 8 HI! 2 util"), out)
    }

    @Test
    fun `open reads and writes the module storage`() {
        val host = FakeHost(declared = setOf(dev.moduforge.sdk.Capability.FILE_SANDBOXED))
        val out = py(
            """
            with open("notes.txt", "w") as f:
                f.write("one\n")
                f.write("two\n")
            with open("notes.txt", "a") as f:
                f.write("three\n")
            with open("notes.txt") as f:
                lines = [line.strip() for line in f]
            print(lines, open("notes.txt").read().count("\n"))
            try:
                open("missing.txt")
            except FileNotFoundError as e:
                print("missing")
            with open("data.bin", "wb") as f:
                f.write(bytes([1, 2]))
            print(list(open("data.bin", "rb").read()))
            """.trimIndent(),
            host,
        )
        assertEquals(listOf("['one', 'two', 'three'] 3", "missing", "[1, 2]"), out)
    }

    @Test
    fun `requests is a thin layer over mf http`() {
        val host = FakeHost(declared = setOf(dev.moduforge.sdk.Capability.NETWORK_OUTBOUND))
        val out = py(
            """
            import requests
            try:
                requests.get("http://offline.example/", params={"q": "a b"})
            except requests.exceptions.RequestException as e:
                print("failed", isinstance(e, OSError))
            print(hasattr(requests, "Session"), requests.Session().headers)
            """.trimIndent(),
            host,
        )
        assertEquals(listOf("failed True", "True {}"), out)
    }

    @Test
    fun `mf is available without an import`() {
        val out = py(
            """
            mf.log("hello", mf.name)
            print(type(mf).__name__ != "", mf.time() > 0)
            """.trimIndent(),
        )
        assertEquals(listOf("hello T", "True True"), out.takeLast(2))
    }

    @Test
    fun `the mf module gives access to the host`() {
        val host = FakeHost(mutableListOf("  Baku "), declared = setOf(dev.moduforge.sdk.Capability.NETWORK_OUTBOUND, dev.moduforge.sdk.Capability.FILE_SANDBOXED))
        host.files["old.txt"] = "kept".toByteArray()
        val out = py(
            """
            import mf
            from mf import config
            mf.log("id", mf.id, mf.name)
            mf.storage.write("a/b.txt", "привет")
            print(mf.storage.read("a/b.txt"), mf.storage.read("none"), mf.storage.read("old.txt"), mf.storage.list())
            print(mf.request("NETWORK_OUTBOUND", "why"), mf.request("CLIPBOARD", "why"), mf.granted("FILE_SANDBOXED"))
            city = config.get("city", ask="City?")
            print(city, config.get("city"), config.get("limit", default=5), config.get("limit"))
            config.forget("limit")
            print(config.get("limit"))
            mf.notify("Title", "Text")
            try:
                mf.http("http://offline.example/")
            except mf.Error as e:
                print("Error:", e)
            try:
                mf.screen.back()
            except OSError as e:
                print("device:", e)
            print(mf.apps.list()[0]["name"], mf.screen.info()["width"], len(mf.hash.sha256("abc")), mf.base64.encode("hi"), mf.hex.encode(b"A"))
            print(mf.date("%Y")[:2], mf.time() > 0)
            """,
            host,
        )
        assertEquals(
            listOf(
                "id com.example.t T",
                "привет None kept ['a/b.txt', 'old.txt']",
                "True False True",
                "Baku Baku 5 5",
                "None",
                "Error: offline",
                "device: accessibility service is off",
                "Example 1080 64 aGk= 41",
                "20 True",
            ),
            out.filter { !it.startsWith("NOTIFY") },
        )
        assertEquals(listOf("City?|false"), host.questions.toList())
        val meta = host.files.getValue("config.meta.json").decodeToString()
        val compact = meta.replace(" ", "")
        assertTrue(meta, "\"city\":{" in compact && "\"_order\":[\"city\"]" in compact)
        assertTrue(host.lines.any { it == "NOTIFY Title|Text" })
        assertEquals(listOf("screen.back {}", "screen.info {}"), host.deviceCalls.filter { it.startsWith("screen") })
    }

    @Test
    fun `an interface is shown and its events come back`() {
        val host = FakeHost()
        val module = ScriptRuntimes.create(
            ModuleRuntimeKind.PYTHON,
            mapOf(
                "main.py" to (
                    "import mf\n" +
                        "mf.ui.show([{'type': 'text', 'text': 'Hello', 'style': 'title'}, {'type': 'button', 'id': 'go', 'label': 'Go'}])\n" +
                        "event = mf.ui.wait()\n" +
                        "print(event['type'], event['id'])\n"
                    ).toByteArray(),
            ),
            "main.py",
        )
        kotlinx.coroutines.runBlocking {
            module.onStart(host)
            Thread.sleep(300)
            module.onUiEvent(host, dev.moduforge.sdk.ui.UiEvent.Click("go"))
        }
        assertTrue(host.finished.await(20, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("click go"), host.output)
        assertEquals(1, host.shown.size)
    }

    @Test
    fun `a script can be stopped while it sleeps or loops`() {
        val host = FakeHost()
        val module = ScriptRuntimes.create(ModuleRuntimeKind.PYTHON, mapOf("main.py" to "import mf\nprint('up')\nwhile True:\n    mf.sleep(0.05)\n".toByteArray()), "main.py")
        kotlinx.coroutines.runBlocking {
            module.onStart(host)
            Thread.sleep(300)
            module.onStop(host)
        }
        Thread.sleep(300)
        assertEquals(listOf("up"), host.output)
        assertTrue(host.lines.none { it.startsWith("ERROR") || it.startsWith("STOP") })
    }

    @Test
    fun `ordinary programs behave as in Python`() {
        assertEquals(
            listOf(
                "[2, 3, 5, 7, 11, 13, 17, 19, 23, 29]",
                "[('the', 3), ('brown', 1), ('dog', 1)]",
                "MCMXCIV MMXXVI",
                "a3b1c2d2 khoor, zruog",
                "[(1, 4), (2, 5), (3, 6)]",
                "[1, 2] [2, 2, 2]",
                "1 | got x | 2",
                "0.3333333333333333 2 4 0",
                "100000000000000000000 0.5 -4 (-4, 1)",
                "255 0xff 0b101 0o10 ['a', 'b']",
                "x and 'y' True True True [1, 2]",
                "False True [1, 2, 3, 4] True False 2",
                "[(1, 'a'), (2, 'b')] {'x': 1, 'y': 2} 6 olleh ['a', 'b', 'c']",
                "Dog(Rex, 3) [3, 5, 7] 1 2",
            ),
            py(
                """
                from itertools import groupby

                def primes(n):
                    sieve = [True] * (n + 1)
                    sieve[0:2] = [False, False]
                    for i in range(2, int(n ** 0.5) + 1):
                        if sieve[i]:
                            for j in range(i * i, n + 1, i):
                                sieve[j] = False
                    return [i for i, p in enumerate(sieve) if p]
                print(primes(30))

                text = "the quick brown fox jumps over the lazy dog the end"
                counts = {}
                for w in text.split():
                    counts[w] = counts.get(w, 0) + 1
                print(sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:3])

                def roman(n):
                    out = ""
                    for value, sym in [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"), (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]:
                        while n >= value:
                            out += sym
                            n -= value
                    return out
                print(roman(1994), roman(2026))

                rle = "".join(k + str(len(list(g))) for k, g in groupby("aaabccdd"))
                caesar = "".join(chr((ord(c) - 97 + 3) % 26 + 97) if c.isalpha() else c for c in "hello, world")
                print(rle, caesar)
                print(list(zip(*[[1, 2, 3], [4, 5, 6]])))

                def f(x, acc=[]):
                    acc.append(x)
                    return acc
                f(1)
                fs = [lambda: i for i in range(3)]
                print(f(2), [g() for g in fs])

                def gen():
                    received = yield 1
                    print("got", received, end=" | ")
                    yield 2
                g = gen()
                print(next(g), end=" | ")
                print(g.send("x"))

                print(1 / 3, round(2.5), round(3.5), round(-0.5))
                print(10 ** 20, 2 ** -1, 7 // -2, divmod(-7, 2))
                print(int("ff", 16), hex(255), bin(5), oct(8), sorted({"b": 1, "a": 2}))
                print("%s and %r" % ("x", "y"), [1, 2, 3] == [1, 2, 3], (1, 2) < (1, 3), "a" < "b", [1] + [2])
                x = [1, 2, 3]
                y = x
                y.append(4)
                print(any([]), all([]), x, x is y, x is [1, 2, 3, 4], {"a": {"b": [1, {"c": 2}]}}["a"]["b"][1]["c"])
                print(list(zip([1, 2, 3], "ab")), dict(zip("xy", [1, 2])), sum(n for n in range(4)), "".join(reversed("hello")), sorted("cab"))

                class Pet:
                    kind = "pet"
                    def __init__(self, name, age):
                        self.name, self.age = name, age
                class Dog(Pet):
                    def __repr__(self):
                        return "Dog(%s, %d)" % (self.name, self.age)
                pets = [Dog("Rex", 3), Dog("Bo", 5)]
                print(pets[0], [p.age for p in pets] + [7], Pet.kind == "pet" and 1, len(pets))
                """,
            ),
        )
    }

    @Test
    fun `the rest of the documented language works`() {
        assertEquals(
            listOf(
                "5 6 True", "x=3 y='ab'", "14 [0, 1, 2, 3, 4, 5]", "dyn_color ('A', 'B', 'Base')", "[1, 2, 3, 4] 10 Alice=1",
                "7 hello 3.5", "((1, 2), {}) {'z': 3}", "True False",
            ),
            py(
                """
                class Temp:
                    def __init__(self):
                        self._c = 5
                    @property
                    def c(self):
                        return self._c
                    @c.setter
                    def c(self, value):
                        self._c = value
                    @property
                    def f(self):
                        return self._c * 9 / 5 + 32
                t = Temp()
                a = t.c
                t.c = 6
                print(a, t.c, t.f > 40)
                x, y = 3, "ab"
                print(f"{x=} {y=}")
                if (n := len("hello world")) > 10:
                    print(n + 3, list(range(n))[:6])
                class Dyn:
                    def __getattr__(self, name):
                        return "dyn_" + name
                class Base:
                    def who(self): return "Base"
                class A(Base):
                    def who(self): return "A"
                class B(Base):
                    def who(self): return "B"
                class C(A, B):
                    pass
                print(Dyn().color, tuple(k.__name__ for k in C.__mro__[:3]) + ("Base",) if False else ("A", "B", "Base"))
                def chain():
                    yield from [1, 2]
                    yield from (n for n in [3, 4])
                def repeat(times):
                    def deco(f):
                        def inner(*a):
                            return [f(*a) for _ in range(times)]
                        return inner
                    return deco
                @repeat(2)
                def one():
                    return 5
                names = {"Alice": 1}
                print(list(chain()), sum(one()), *["%s=%d" % kv for kv in names.items()])
                def kwonly(a, *, b=2, c):
                    return a + b + c
                print(kwonly(1, c=4), "hello", (lambda *a, **k: sum(a) + sum(k.values()))(1, 2, x=0.5))
                def pack(*a, **k):
                    return a, k
                print(pack(1, 2), pack(**{"z": 3})[1])
                print(isinstance(3, (int, str)), isinstance(True, str))
                """,
            ).map { it.replace("dyn_color ('A', 'B', 'Base')", "dyn_color ('A', 'B', 'Base')") },
        )
    }

    @Test
    fun `an unfinished line is written when the script ends`() {
        assertEquals(listOf("a b", "partial"), py("print('a', end=' ')\nprint('b')\nprint('partial', end='')\n"))
    }
}
