// Types of the `mf` object of ModuForge JavaScript modules (editor help only; not part of the module).

type Permission =
  | "NETWORK_OUTBOUND" | "FILE_SANDBOXED" | "NOTIFICATIONS" | "BACKGROUND_EXECUTION"
  | "LAUNCH_APPS" | "SCREEN_CONTROL" | "CAMERA" | string;

interface HttpRequest {
  url: string;
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  form?: Record<string, string>;
  files?: { field: string; filename: string; type?: string; content: string }[];
  redirects?: number;
}

interface HttpResponse {
  status: number;
  body: string;
  headers: Record<string, string>;
  url: string;
  json(): any;
}

interface Connection {
  read(max?: number, timeout?: number): string | null;
  readExactly(count: number, timeout?: number): string | null;
  readLine(timeout?: number): string | null;
  write(data: string): boolean;
  close(): void;
}

interface WebSocketLike {
  send(data: string): void;
  receive(timeout?: number): string | null;
  ping(): void;
  close(): void;
}

interface UiNode {
  type: "text" | "button" | "input" | "row" | "column";
  id?: string;
  text?: string;
  label?: string;
  style?: "title" | "body" | "caption";
  value?: string;
  children?: UiNode[];
}

interface UiEvent {
  type: "click" | "text";
  id: string;
  value?: string;
}

interface ScreenElement {
  text: string;
  desc: string;
  id: string;
  x: number;
  y: number;
  bounds: number[];
  clickable: boolean;
  found?: boolean;
}

declare const mf: {
  id: string;
  name: string;
  version: string;
  log(...values: any[]): void;
  sleep(seconds: number): void;
  time(): number;
  date(format?: string, time?: number): string;
  granted(permission: Permission, target?: string): boolean;
  request(permission: Permission, reason?: string, target?: string): boolean;
  notify(title: string, text?: string): void;
  ask(question: string, secret?: boolean): string | null;
  urlencode(text: string): string;
  random(count: number): string;
  http(request: string | HttpRequest): HttpResponse;
  connect(host: string, port: number, options?: { tls?: boolean }): Connection;
  websocket(url: string, headers?: Record<string, string>): WebSocketLike;
  storage: {
    read(path: string): string | null;
    write(path: string, data: string): boolean;
    delete(path: string): boolean;
    list(): string[];
  };
  hash: Record<"sha256" | "md5" | "sha1" | "sha512", (data: string, raw?: boolean) => string>;
  hmac: Record<"sha256" | "md5" | "sha1" | "sha512", (key: string, data: string, raw?: boolean) => string>;
  base64: { encode(data: string, url?: boolean): string; decode(text: string): string };
  hex: { encode(data: string): string; decode(text: string): string };
  ui: {
    show(tree: UiNode | UiNode[]): void;
    wait(timeout?: number): UiEvent | null;
    clear(): void;
  };
  apps: {
    list(): { package: string; name: string }[];
    launch(app: string): { ok: boolean; package: string };
    open(url: string): void;
    installed(pkg: string): boolean;
  };
  screen: {
    info(): { width: number; height: number; package: string; enabled: boolean };
    tap(x: number, y: number, ms?: number): void;
    press(x: number, y: number, ms?: number): void;
    swipe(x1: number, y1: number, x2: number, y2: number, ms?: number): void;
    back(): void;
    home(): void;
    recents(): void;
    notifications(): void;
    texts(): ScreenElement[];
    find(text: string): ScreenElement[];
    click(text: string): void;
    type(text: string): void;
    wait(text: string, timeout?: number): ScreenElement;
    event(timeout?: number): any;
  };
  camera: {
    list(): { lens: "back" | "front"; id: string }[];
    photo(path: string, lens?: string, size?: number, quality?: number, flash?: boolean):
      { ok: boolean; path: string; width: number; height: number; bytes: number };
  };
  config: {
    get(key: string, options?: { ask?: string; label?: string; secret?: boolean; default?: any; save?: boolean }): any;
    set(key: string, value: any): void;
    forget(key: string): void;
  };
  schedule: {
    every(seconds: number, task: () => void, options?: { immediately?: boolean }): void;
    step(): number;
    run(): never;
  };
};

declare function require(path: string): any;
