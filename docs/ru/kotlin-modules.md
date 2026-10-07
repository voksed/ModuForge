# Модули на Kotlin

Компилируемый модуль — это класс, реализующий `dev.moduforge.sdk.Module`. В отличие от
скрипта на Lua, он может реагировать на каждый шаг жизненного цикла и показывать
собственный интерфейс. Работает он в той же песочнице и получает те же сервисы хоста.

Начните с шаблона: папка [`templates/kotlin-module`](../../templates/kotlin-module) — полный
проект, который собирается сам по себе, вне этого репозитория. `modules/hello` — пример
побольше, он проходит весь жизненный цикл.

```
gradlew :sdk:publishToMavenLocal     один раз, в клоне этого репозитория: делает SDK доступным
cd моя-копия-шаблона
gradlew assembleRelease
mfrg push src/main/assets --dex build/outputs/apk/release/my-module-release-unsigned.apk
```

`mfrg push` ставит модуль на телефон и перезапускает его одним шагом — так же, как для
скриптов (см. [Как писать модули](writing-modules.md#отправить-на-телефон-mfrg-push));
первая отправка модуля, которому нужны разрешения, ждёт, пока вы один раз нажмёте
«Запустить» в приложении.

## Проект

Проект модуля — это модуль Android-приложения, который служит только контейнером для
скомпилированного кода. В систему он никогда не устанавливается.

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mymodule"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.mymodule"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
}

// SDK и рантайм Kotlin предоставляет песочница.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
}

dependencies {
    compileOnly(project(":sdk"))
}
```

Вне этого репозитория опубликуйте SDK в локальный Maven-репозиторий командой
`gradlew :sdk:publishToMavenLocal`, добавьте `mavenLocal()` в репозитории и подключите
`dev.moduforge:moduforge-sdk:1.0.0` (`compileOnly`). В публичный репозиторий SDK не
опубликован. Всё это уже сделано в шаблоне; он же добавляет плагин Kotlin для Gradle с
`apply false`, благодаря чему сборка использует ту же версию Kotlin, что и SDK.

Манифест лежит в `src/main/assets/moduforge.json`: `"runtime": "dex"` (или без поля
`runtime`) и имя класса в `entry`. Поля описаны в разделе
[Как писать модули](writing-modules.md#манифест).

## Упаковка

```
gradlew :modules:hello:assembleRelease
mfrg pack modules/hello/src/main/assets --dex modules/hello/build/outputs/apk/release/hello-release-unsigned.apk
```

`--dex` достаёт скомпилированные классы из APK; папка даёт `moduforge.json`.

## Класс модуля

```kotlin
class MyModule : Module {
    private var work: CoroutineScope? = null

    override suspend fun onStart(context: ModuleContext) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { work = it }
        scope.launch {
            val connection = context.network.connect("example.com", 443, tls = true)
            // …
        }
    }

    override suspend fun onStop(context: ModuleContext) {
        work?.cancel()
    }
}
```

У класса должен быть публичный конструктор без аргументов.

| Метод | Когда вызывается | Где выполняется |
|---|---|---|
| `onInstall` | После установки | Короткоживущая песочница, уничтожается после возврата из метода |
| `onEnable` | Пользователь включил модуль | Короткоживущая песочница |
| `onStart` | Модуль запускают | Песочница, которая остаётся жить |
| `onUiEvent` | Пользователь что-то сделал в интерфейсе модуля | Та же песочница, только между запуском и остановкой |
| `onStop` | Модуль останавливают | Та же песочница; после этого она уничтожается |
| `onDisable` | Пользователь отключил модуль | Короткоживущая песочница |
| `onUninstall` | Перед удалением | Короткоживущая песочница |

Правила:

- **Методы должны возвращаться быстро.** Метод, работающий дольше 10 секунд, считается
  неудавшимся; неудавшийся `onStart` означает, что модуль не запустился. Долгую работу и
  всё, что ждёт пользователя, выполняйте в корутине, запущенной из `onStart`.
- Метод, бросивший исключение, записывается в журнал аудита и никогда не блокирует
  пользователя: остановка, отключение и удаление проходят всегда.
- Каждая короткоживущая песочница — новый процесс. Поля вашего класса не сохраняются
  между `onInstall`, `onEnable` и `onStart`; состояние держите в `context.storage`.
- После `onStop` процесс уничтожается независимо от того, закончил ли ваш код.

## `ModuleContext`

Единственная связь модуля с внешним миром. Каждый член — это обращение к хосту.

| Член | Назначение | Разрешение |
|---|---|---|
| `manifest` | Собственный манифест модуля | — |
| `log` | `info`, `warn`, `error` — вывод модуля | — |
| `capabilities` | `request(CapabilityRequest)`, `isGranted(capability)` | — |
| `network` | `connect(host, port, tls)` → `Connection` с потоками `input` и `output` | `NETWORK_OUTBOUND` |
| `storage` | `read`, `write`, `delete`, `list` | `FILE_SANDBOXED` |
| `notifications` | `notify(title, text)` | `NOTIFICATIONS` |
| `prompt` | `ask(question, secret)` → ответ пользователя или null | — |
| `ui` | `show(tree)`, `clear()` | в манифесте `"ui": "compose"` |
| `stopSelf(reason)` | Попросить хост остановить модуль | — |

Сервис, вызванный без своего разрешения, бросает `CapabilityNotGrantedException`. Сбои
сети и хранилища бросают `IOException`.

`network.connect` даёт сырой поток байтов. При `tls = true` хост сам выполняет
TLS-рукопожатие и проверку сертификата, а поток несёт расшифрованные данные.
HTTP-клиента в SDK нет: пишите запрос сами или включите в модуль клиент на чистом Kotlin,
умеющий работать с потоками.

## Интерфейс

Песочница не может создавать окна, поэтому модуль не рисует. Он описывает интерфейс
деревом; приложение отображает дерево в рамке с пометкой «содержимое модуля» и сообщает,
что сделал пользователь. Укажите в манифесте `"ui": "compose"`.

```kotlin
private var clicks = 0

override suspend fun onStart(context: ModuleContext) = render(context)

override suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {
    if (event is UiEvent.Click && event.id == "count") clicks++
    render(context)
}

private fun render(context: ModuleContext) {
    context.ui.show(
        column {
            text("Счётчик", TextStyle.TITLE)
            row {
                button("count", "Считать")
                text("Нажатий: $clicks")
            }
        },
    )
}
```

| Узел | Построитель | Примечания |
|---|---|---|
| Колонка | `column { … }` | Дочерние элементы друг под другом |
| Ряд | `row { … }` | Дочерние элементы рядом |
| Текст | `text(text, style)` | Стили: `TITLE`, `BODY`, `CAPTION`, `CODE` |
| Кнопка | `button(id, label, enabled)` | Присылает `UiEvent.Click(id)` |
| Поле ввода | `textField(id, value, label)` | Присылает `UiEvent.TextChanged(id, value)` при каждой правке |

Чтобы изменить показанное, вызовите `show` снова с новым деревом. В дереве может быть не
больше 500 узлов и 256 КБ в сериализованном виде; большее дерево отклоняется, о чём
появляется строка в выводе модуля.

Для поля ввода возвращайте то значение, которое получили. Если передать другое, оно
заменит набранное пользователем.

## Что доступно коду модуля

- Классы платформы Android и стандартная библиотека Kotlin с корутинами.
- `dev.moduforge.sdk.*`.
- Любая библиотека на чистой JVM, скомпилированная в модуль.

Недоступны: AndroidX и остальные библиотеки приложения-хоста, нативные библиотеки и всё,
чему нужен Android `Context`, путь к файлу или сокет, — у процесса нет разрешений, каталога
данных и сети. См. [Модель безопасности](security.md).
