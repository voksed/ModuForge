"""A small `requests` on top of mf.http: get/post/..., Session, Response, the usual exceptions.

It needs the NETWORK_OUTBOUND permission like mf.http does; the module's address rules apply.
"""
import json as _json

import mf


class RequestException(OSError):
    pass


class ConnectionError(RequestException):
    pass


class Timeout(RequestException):
    pass


class HTTPError(RequestException):
    def __init__(self, message="", response=None):
        RequestException.__init__(self, message)
        self.response = response


class exceptions:
    RequestException = RequestException
    ConnectionError = ConnectionError
    Timeout = Timeout
    HTTPError = HTTPError


class Response:
    def __init__(self, raw):
        self._raw = raw
        self.status_code = raw.status
        self.headers = raw.headers
        self.url = raw.url
        self.text = raw.text

    @property
    def content(self):
        return self._raw.bytes()

    @property
    def ok(self):
        return self.status_code < 400

    def json(self):
        return self._raw.json()

    def raise_for_status(self):
        if self.status_code >= 400:
            raise HTTPError(str(self.status_code) + " error for url: " + str(self.url), self)

    def __repr__(self):
        return "<Response [" + str(self.status_code) + "]>"

    def __bool__(self):
        return self.ok


def _with_params(url, params):
    if not params:
        return url
    parts = []
    for key in params:
        value = params[key]
        values = value if isinstance(value, (list, tuple)) else [value]
        for item in values:
            parts.append(mf.urlencode(str(key)) + "=" + mf.urlencode(str(item)))
    return url + ("&" if "?" in url else "?") + "&".join(parts)


def request(method, url, params=None, data=None, json=None, headers=None, timeout=None,
            allow_redirects=True, auth=None, files=None, **ignored):
    send_headers = dict(headers or {})
    body = None
    form = None
    if json is not None:
        body = _json.dumps(json)
        send_headers.setdefault("Content-Type", "application/json")
    elif isinstance(data, dict):
        form = data
    elif data is not None:
        body = data
    if auth is not None:
        send_headers["Authorization"] = "Basic " + mf.base64.encode(str(auth[0]) + ":" + str(auth[1]))
    try:
        raw = mf.http(
            _with_params(url, params),
            method=method.upper(),
            headers=send_headers or None,
            body=body,
            form=form,
            files=files,
            redirects=5 if allow_redirects else 0,
        )
    except TimeoutError as error:
        raise Timeout(str(error))
    except mf.Error as error:
        raise ConnectionError(str(error))
    return Response(raw)


def get(url, params=None, **kwargs):
    return request("GET", url, params=params, **kwargs)


def post(url, data=None, json=None, **kwargs):
    return request("POST", url, data=data, json=json, **kwargs)


def put(url, data=None, **kwargs):
    return request("PUT", url, data=data, **kwargs)


def patch(url, data=None, **kwargs):
    return request("PATCH", url, data=data, **kwargs)


def delete(url, **kwargs):
    return request("DELETE", url, **kwargs)


def head(url, **kwargs):
    return request("HEAD", url, **kwargs)


class Session:
    def __init__(self):
        self.headers = {}

    def request(self, method, url, headers=None, **kwargs):
        merged = dict(self.headers)
        merged.update(headers or {})
        return request(method, url, headers=merged, **kwargs)

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, **kwargs):
        return self.request("POST", url, **kwargs)

    def put(self, url, **kwargs):
        return self.request("PUT", url, **kwargs)

    def delete(self, url, **kwargs):
        return self.request("DELETE", url, **kwargs)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False
