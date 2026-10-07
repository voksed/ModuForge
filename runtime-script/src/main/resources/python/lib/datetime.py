"""`datetime`, `date` and `timedelta` for naive local times, enough for scheduling and logging."""
import time

_DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]


def _is_leap(year):
    return year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)


def _days_in_month(year, month):
    return 29 if month == 2 and _is_leap(year) else _DAYS_IN_MONTH[month - 1]


def _days_from_civil(year, month, day):
    year -= month <= 2
    era = (year if year >= 0 else year - 399) // 400
    year_of_era = year - era * 400
    shifted = month + (-3 if month > 2 else 9)
    day_of_year = (153 * shifted + 2) // 5 + day - 1
    day_of_era = year_of_era * 365 + year_of_era // 4 - year_of_era // 100 + day_of_year
    return era * 146097 + day_of_era - 719468


def _civil_from_days(days):
    days += 719468
    era = (days if days >= 0 else days - 146096) // 146097
    day_of_era = days - era * 146097
    year_of_era = (day_of_era - day_of_era // 1460 + day_of_era // 36524 - day_of_era // 146096) // 365
    year = year_of_era + era * 400
    day_of_year = day_of_era - (365 * year_of_era + year_of_era // 4 - year_of_era // 100)
    shifted = (5 * day_of_year + 2) // 153
    day = day_of_year - (153 * shifted + 2) // 5 + 1
    month = shifted + (3 if shifted < 10 else -9)
    return year + (month <= 2), month, day


class timedelta:
    def __init__(self, days=0, seconds=0, microseconds=0, milliseconds=0, minutes=0, hours=0, weeks=0):
        total = ((((weeks * 7 + days) * 24 + hours) * 60 + minutes) * 60 + seconds) + milliseconds / 1000 + microseconds / 1000000
        self._seconds = total
        self.days = int(total // 86400)
        self.seconds = int(total - self.days * 86400)
        self.microseconds = int(round((total - int(total)) * 1000000))

    def total_seconds(self):
        return float(self._seconds)

    def __add__(self, other):
        if isinstance(other, timedelta):
            return timedelta(seconds=self._seconds + other._seconds)
        return NotImplemented

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return timedelta(seconds=self._seconds - other._seconds)
        return NotImplemented

    def __neg__(self):
        return timedelta(seconds=-self._seconds)

    def __mul__(self, factor):
        return timedelta(seconds=self._seconds * factor)

    def __truediv__(self, divisor):
        if isinstance(divisor, timedelta):
            return self._seconds / divisor._seconds
        return timedelta(seconds=self._seconds / divisor)

    def __eq__(self, other):
        return isinstance(other, timedelta) and self._seconds == other._seconds

    def __lt__(self, other):
        return self._seconds < other._seconds

    def __le__(self, other):
        return self._seconds <= other._seconds

    def __gt__(self, other):
        return self._seconds > other._seconds

    def __ge__(self, other):
        return self._seconds >= other._seconds

    def __bool__(self):
        return self._seconds != 0

    def __repr__(self):
        return "datetime.timedelta(days=" + str(self.days) + ", seconds=" + str(self.seconds) + ")"

    def __str__(self):
        hours, rest = divmod(self.seconds, 3600)
        minutes, seconds = divmod(rest, 60)
        clock = str(hours) + ":" + str(minutes).zfill(2) + ":" + str(seconds).zfill(2)
        if self.days:
            return str(self.days) + " day" + ("" if abs(self.days) == 1 else "s") + ", " + clock
        return clock


class date:
    def __init__(self, year, month=1, day=1):
        if not 1 <= month <= 12:
            raise ValueError("month must be in 1..12")
        if not 1 <= day <= _days_in_month(year, month):
            raise ValueError("day is out of range for month")
        self.year = year
        self.month = month
        self.day = day

    @classmethod
    def today(cls):
        now = time.localtime()
        return cls(now[0], now[1], now[2])

    def toordinal(self):
        return _days_from_civil(self.year, self.month, self.day) + 719163

    def weekday(self):
        return (_days_from_civil(self.year, self.month, self.day) + 3) % 7

    def isoweekday(self):
        return self.weekday() + 1

    def isoformat(self):
        return str(self.year).zfill(4) + "-" + str(self.month).zfill(2) + "-" + str(self.day).zfill(2)

    def strftime(self, fmt):
        return time.strftime(fmt, (self.year, self.month, self.day, 0, 0, 0, self.weekday(), 0, 0))

    def replace(self, year=None, month=None, day=None):
        return date(year or self.year, month or self.month, day or self.day)

    def __add__(self, other):
        if isinstance(other, timedelta):
            y, m, d = _civil_from_days(_days_from_civil(self.year, self.month, self.day) + int(other.total_seconds() // 86400))
            return date(y, m, d)
        return NotImplemented

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return self + (-other)
        if isinstance(other, date):
            return timedelta(days=self.toordinal() - other.toordinal())
        return NotImplemented

    def _key(self):
        return (self.year, self.month, self.day)

    def __eq__(self, other):
        return isinstance(other, date) and self._key() == other._key()

    def __lt__(self, other):
        return self._key() < other._key()

    def __le__(self, other):
        return self._key() <= other._key()

    def __gt__(self, other):
        return self._key() > other._key()

    def __ge__(self, other):
        return self._key() >= other._key()

    def __hash__(self):
        return hash(self._key())

    def __repr__(self):
        return "datetime.date(" + str(self.year) + ", " + str(self.month) + ", " + str(self.day) + ")"

    def __str__(self):
        return self.isoformat()


class datetime(date):
    def __init__(self, year, month=1, day=1, hour=0, minute=0, second=0, microsecond=0):
        date.__init__(self, year, month, day)
        self.hour = hour
        self.minute = minute
        self.second = second
        self.microsecond = microsecond

    @classmethod
    def fromtimestamp(cls, timestamp):
        fields = time.localtime(timestamp)
        return cls(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], int(round((timestamp % 1) * 1000000)) % 1000000)

    @classmethod
    def utcfromtimestamp(cls, timestamp):
        fields = time.gmtime(timestamp)
        return cls(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])

    @classmethod
    def now(cls):
        return cls.fromtimestamp(time.time())

    @classmethod
    def utcnow(cls):
        return cls.utcfromtimestamp(time.time())

    @classmethod
    def today(cls):
        return cls.now()

    def date(self):
        return date(self.year, self.month, self.day)

    def _seconds(self):
        return _days_from_civil(self.year, self.month, self.day) * 86400 + self.hour * 3600 + self.minute * 60 + self.second + self.microsecond / 1000000

    @classmethod
    def _from_seconds(cls, seconds):
        days = int(seconds // 86400)
        rest = seconds - days * 86400
        y, m, d = _civil_from_days(days)
        whole = int(rest)
        return cls(y, m, d, whole // 3600, whole % 3600 // 60, whole % 60, int(round((rest - whole) * 1000000)) % 1000000)

    def timestamp(self):
        return time.mktime((self.year, self.month, self.day, self.hour, self.minute, self.second, 0, 0, 0)) + self.microsecond / 1000000

    def isoformat(self, sep="T"):
        text = date.isoformat(self) + sep + str(self.hour).zfill(2) + ":" + str(self.minute).zfill(2) + ":" + str(self.second).zfill(2)
        if self.microsecond:
            text += "." + str(self.microsecond).zfill(6)
        return text

    def strftime(self, fmt):
        return time.strftime(fmt, (self.year, self.month, self.day, self.hour, self.minute, self.second, self.weekday(), 0, 0))

    def replace(self, year=None, month=None, day=None, hour=None, minute=None, second=None, microsecond=None):
        return datetime(
            self.year if year is None else year, self.month if month is None else month, self.day if day is None else day,
            self.hour if hour is None else hour, self.minute if minute is None else minute,
            self.second if second is None else second, self.microsecond if microsecond is None else microsecond,
        )

    def __add__(self, other):
        if isinstance(other, timedelta):
            return datetime._from_seconds(self._seconds() + other.total_seconds())
        return NotImplemented

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return datetime._from_seconds(self._seconds() - other.total_seconds())
        if isinstance(other, datetime):
            return timedelta(seconds=self._seconds() - other._seconds())
        return NotImplemented

    def _key(self):
        return (self.year, self.month, self.day, self.hour, self.minute, self.second, self.microsecond)

    def __repr__(self):
        return "datetime.datetime(" + ", ".join(str(part) for part in self._key()) + ")"

    def __str__(self):
        return self.isoformat(" ")


MINYEAR = 1
MAXYEAR = 9999
