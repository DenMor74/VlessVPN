# VlessVPN — Android VPN приложение

Приложение автоматически скачивает списки VLESS серверов, тестирует их через мобильный интернет и показывает топ-10 самых быстрых. Подключение к VPN — одной кнопкой.

---

## 📋 Что делает приложение

1. **Скачивает** списки VLESS серверов с заданных URL по расписанию
2. **Тестирует** каждый сервер:
   - TCP ping (открыт ли порт?)
   - HTTP трафик через временный v2ray (идёт ли данные?)
   - **ТОЛЬКО через мобильный интернет** (не WiFi)
3. **Хранит** топ-10 самых быстрых рабочих серверов в базе данных
4. **Подключает** к выбранному серверу одной кнопкой

---

## 🔧 Установка в Android Studio

### Шаг 1: Скачать libv2ray.aar

Это самый важный шаг! Без этого файла приложение не скомпилируется.

**Вариант A (рекомендуется): Из AndroidLibV2rayLite**
```
1. Открыть https://github.com/2dust/AndroidLibV2rayLite
2. Раздел Actions → последний успешный workflow
3. Скачать артефакт "aar"
4. Распаковать → взять libv2ray.aar
5. Положить в папку: app/libs/libv2ray.aar
```

**Вариант B: Собрать самому (Go нужен)**
```bash
git clone https://github.com/2dust/AndroidLibV2rayLite
cd AndroidLibV2rayLite
make
# libv2ray.aar будет в папке build/
```

### Шаг 2: Открыть проект

```
Android Studio → File → Open → выбрать папку VlessVPN
```

### Шаг 3: Убедиться что libv2ray.aar на месте

```
app/
└── libs/
    └── libv2ray.aar  ← должен быть здесь!
```

### Шаг 4: Синхронизировать Gradle

```
Android Studio → File → Sync Project with Gradle Files
```

### Шаг 5: Запустить на устройстве

```
Run → Run 'app' (или Shift+F10)
```

> ⚠️ VPN не работает в эмуляторе! Нужно реальное устройство Android.

---

## 🏗️ Структура проекта

```
app/src/main/java/com/vlessvpn/app/
├── VpnApplication.java          — Класс приложения (инициализация)
│
├── model/
│   └── VlessServer.java         — Модель сервера + парсер VLESS URI
│
├── storage/
│   ├── AppDatabase.java         — Room база данных
│   ├── ServerDao.java           — SQL запросы (интерфейс)
│   └── ServerRepository.java    — Репозиторий (слой данных)
│
├── network/
│   ├── ConfigDownloader.java    — Скачивание списков серверов
│   └── ServerTester.java        — Тестирование ping + трафика
│
├── core/
│   ├── V2RayConfigBuilder.java  — Генерация JSON конфига для v2ray
│   └── V2RayManager.java        — Управление libv2ray нативной библиотекой
│
├── service/
│   ├── VpnTunnelService.java    — VpnService (TUN интерфейс + v2ray)
│   ├── BackgroundMonitorService.java — WorkManager + фоновые задачи
│   └── BootReceiver.java        — Запуск при перезагрузке
│
└── ui/
    ├── MainActivity.java        — Главный экран
    ├── MainViewModel.java       — ViewModel для MainActivity
    ├── ServerAdapter.java       — RecyclerView адаптер
    └── SettingsActivity.java    — Экран настроек
```

---

## 🔌 Как работает VPN (технически)

```
Приложение
    │
    ▼
VpnTunnelService
    │
    ├── builder.establish() → TUN интерфейс (fd=5)
    │       │
    │       │  Весь трафик телефона → TUN
    │
    └── V2RayManager.start(server, port=10808, tunFd=5)
            │
            │  libv2ray.aar (нативная Go библиотека):
            │  - Читает пакеты из TUN (fd=5)
            │  - Оборачивает в VLESS протокол
            │  - Отправляет на сервер через реальный интернет
            │
            ▼
        VLESS сервер (Reality/TLS/TCP)
            │
            ▼
        Целевой сайт (YouTube, etc.)
```

---

## ⚙️ Настройки

В экране настроек можно задать:
- **URL списков серверов** — один URL на строку
- **Интервал обновления** — как часто скачивать новые списки (1-24 часа)

По умолчанию используется:
```
https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/vless_all.txt
```

---

## 🐛 Частые проблемы

### "Не удалось запустить V2Ray (handle=0)"
→ Проблема с libv2ray.aar:
- Убедись что файл в папке `app/libs/`
- Пересобери проект: Build → Clean Project → Rebuild Project

### "Нет рабочих серверов"
→ Проблемы с сетью или серверами:
- Убедись что есть мобильный интернет (не только WiFi)
- Потяни вниз для обновления или нажми кнопку в меню
- Проверь URL в настройках

### "Разрешение VPN отклонено"
→ Пользователь нажал "Нет" в системном диалоге
- Нажми "Подключить" снова и разреши VPN

### Приложение не запускается на эмуляторе
→ VPN Service не работает в эмуляторе — нужен реальный телефон

---

## 📦 Зависимости

| Библиотека | Версия | Назначение |
|-----------|--------|-----------|
| libv2ray.aar | Latest | V2Ray core (VLESS/Reality) |
| Room | 2.6.1 | База данных серверов |
| WorkManager | 2.9.0 | Фоновые задачи |
| OkHttp | 4.12.0 | HTTP запросы |
| Gson | 2.10.1 | JSON сериализация |
| Material | 1.11.0 | UI компоненты |
| Lifecycle | 2.7.0 | ViewModel + LiveData |

---

## 📱 Требования

- Android 8.0+ (API 26)
- Реальное устройство (не эмулятор)
- Мобильный интернет для тестирования серверов
