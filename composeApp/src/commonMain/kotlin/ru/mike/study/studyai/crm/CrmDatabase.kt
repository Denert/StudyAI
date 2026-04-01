package ru.mike.study.studyai.crm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object CrmDatabase {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dbFile = File(System.getProperty("user.home") + "/.studyai/crm/tickets.json")

    private const val EXPECTED_COUNT = 10

    fun initialize() {
        dbFile.parentFile?.mkdirs()
        val seed = seedTickets()
        val newContent = json.encodeToString(seed)
        if (!dbFile.exists() || dbFile.readText() != newContent) {
            dbFile.writeText(newContent)
            System.err.println("CRM: База пересоздана (${seed.size} тикетов)")
        } else {
            System.err.println("CRM: База актуальна (${seed.size} тикетов)")
        }
    }

    fun loadAll(): List<CrmTicket> = try {
        if (dbFile.exists()) json.decodeFromString<List<CrmTicket>>(dbFile.readText())
        else emptyList()
    } catch (e: Exception) {
        System.err.println("CRM: Ошибка загрузки: ${e.message}")
        emptyList()
    }

    fun findByNumber(number: Int): CrmTicket? = loadAll().find { it.number == number }

    fun search(query: String): List<CrmTicket> {
        val q = query.lowercase()
        return loadAll().filter { t ->
            t.title.lowercase().contains(q) ||
            t.description.lowercase().contains(q) ||
            t.tags.any { it.lowercase().contains(q) }
        }
    }

    private fun seedTickets(): List<CrmTicket> = listOf(
        CrmTicket(
            id = "ticket-001",
            number = 1,
            title = "Неверный расчёт аннуитетного платежа",
            description = "При вводе суммы кредита 500 000 руб. на 24 месяца со ставкой 12% годовых ежемесячный платёж рассчитывается неверно. Приложение показывает 23 500 руб., хотя правильное значение — 23 536 руб. Ошибка в округлении формулы аннуитета.",
            priority = TicketPriority.CRITICAL,
            status = TicketStatus.OPEN,
            user = TicketUser("u1", "Иван Петров", "ivan.petrov@example.com"),
            createdAt = "2025-03-01T10:00:00Z",
            tags = listOf("расчёт", "аннуитет", "баг"),
            stepsToReproduce = "1. Открыть калькулятор\n2. Ввести: сумма 500 000, срок 24 мес., ставка 12%\n3. Нажать «Рассчитать»\n4. Наблюдать неверный результат в поле «Ежемесячный платёж»",
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-002",
            number = 2,
            title = "Краш при вводе дробной ставки с запятой",
            description = "Если ввести процентную ставку с запятой вместо точки (например, «10,5» вместо «10.5»), приложение вылетает с NumberFormatException. Проблема воспроизводится на Android 12 и выше. Нужна валидация поля ввода.",
            priority = TicketPriority.HIGH,
            status = TicketStatus.OPEN,
            user = TicketUser("u2", "Мария Сидорова", "m.sidorova@example.com"),
            createdAt = "2025-03-03T09:15:00Z",
            tags = listOf("краш", "валидация", "ввод"),
            stepsToReproduce = "1. Открыть поле «Процентная ставка»\n2. Ввести «10,5» (запятая как разделитель)\n3. Нажать «Рассчитать»\n4. Приложение падает",
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-003",
            number = 3,
            title = "История кредитов не сохраняется",
            description = "Раздел «История» остаётся пустым после нескольких расчётов. Данные не записываются в локальную базу Room. При перезапуске приложения все ранее введённые кредиты пропадают.",
            priority = TicketPriority.HIGH,
            status = TicketStatus.OPEN,
            user = TicketUser("u3", "Алексей Козлов", "a.kozlov@example.com"),
            createdAt = "2025-03-05T14:30:00Z",
            tags = listOf("история", "БД", "Room", "сохранение"),
            stepsToReproduce = "1. Рассчитать кредит 3–4 раза\n2. Перейти в раздел «История»\n3. Список пустой\n4. Перезапустить — данные не восстанавливаются",
            affectedVersion = "2.0.5"
        ),
        CrmTicket(
            id = "ticket-004",
            number = 4,
            title = "Кнопка «Экспорт PDF» не работает на Android 11+",
            description = "При нажатии на кнопку экспорта результатов в PDF ничего не происходит. В логах ошибка: «Permission denied: /storage/emulated/0/Downloads». Начиная с Android 11, требуется использовать MediaStore API вместо прямого доступа к файловой системе.",
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.OPEN,
            user = TicketUser("u4", "Елена Новикова", "e.novikova@example.com"),
            createdAt = "2025-03-07T11:00:00Z",
            tags = listOf("PDF", "экспорт", "разрешения", "Android 11"),
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-005",
            number = 5,
            title = "Дифференцированные платежи рассчитываются как аннуитетные",
            description = "В режиме дифференцированных платежей все 12 платежей отображаются одинаковыми, хотя должны убывать. Формула не учитывает остаток основного долга на каждом шаге. Итоговая переплата также неверна.",
            priority = TicketPriority.CRITICAL,
            status = TicketStatus.OPEN,
            user = TicketUser("u5", "Дмитрий Фёдоров", "d.fedorov@example.com"),
            createdAt = "2025-03-08T16:20:00Z",
            tags = listOf("расчёт", "дифференцированный", "формула", "баг"),
            stepsToReproduce = "1. Выбрать тип «Дифференцированный»\n2. Ввести: сумма 1 000 000, срок 12 мес., ставка 15%\n3. Нажать «Рассчитать»\n4. Все 12 платежей одинаковые — ошибка",
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-006",
            number = 6,
            title = "Авторизация через Google не работает",
            description = "Кнопка «Войти через Google» не открывает диалог авторизации. В логах: «Google Sign-In failed: DEVELOPER_ERROR». Проблема предположительно в неверном SHA-1 ключе в Firebase Console или в некорректном OAuth client ID.",
            priority = TicketPriority.HIGH,
            status = TicketStatus.OPEN,
            user = TicketUser("u6", "Ольга Смирнова", "o.smirnova@example.com"),
            createdAt = "2025-03-10T10:45:00Z",
            tags = listOf("авторизация", "Google", "Firebase", "OAuth"),
            stepsToReproduce = "1. Открыть экран входа\n2. Нажать «Войти через Google»\n3. Диалог выбора аккаунта не появляется\n4. В Logcat: DEVELOPER_ERROR",
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-007",
            number = 7,
            title = "Тёмная тема: поля ввода остаются белыми",
            description = "При включении тёмной темы поля ввода суммы, срока и ставки отображаются с белым фоном, что нечитаемо. Также фоны карточек и текст вторичных кнопок не обновляются. Причина — не применяется colorSurface из Material3 DarkColorScheme.",
            priority = TicketPriority.LOW,
            status = TicketStatus.OPEN,
            user = TicketUser("u7", "Сергей Морозов", "s.morozov@example.com"),
            createdAt = "2025-03-12T13:00:00Z",
            tags = listOf("UI", "тёмная тема", "Material3", "цвета"),
            affectedVersion = "2.0.5"
        ),
        CrmTicket(
            id = "ticket-008",
            number = 8,
            title = "График платежей пустой при сроке свыше 60 месяцев",
            description = "При сроке кредита более 60 месяцев вкладка «График» пустая. При сроке до 60 месяцев работает нормально. Предположительно, ограничение в конфигурации MPAndroidChart — максимум 60 точек на оси X не задан явно.",
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.OPEN,
            user = TicketUser("u2", "Мария Сидорова", "m.sidorova@example.com"),
            createdAt = "2025-03-14T09:30:00Z",
            tags = listOf("график", "UI", "MPAndroidChart", "срок"),
            stepsToReproduce = "1. Ввести срок 72 месяца\n2. Нажать «Рассчитать»\n3. Перейти на вкладку «График»\n4. График не отображается",
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-009",
            number = 9,
            title = "Push-уведомления о платежах не приходят",
            description = "Пользователи не получают напоминания о предстоящих платежах, несмотря на включённые уведомления в настройках. FCM токен успешно регистрируется, но сервер не отправляет сообщения. Предположительно, не настроен триггер в Cloud Functions.",
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.OPEN,
            user = TicketUser("u8", "Татьяна Белова", "t.belova@example.com"),
            createdAt = "2025-03-15T15:00:00Z",
            tags = listOf("уведомления", "FCM", "push", "Cloud Functions"),
            affectedVersion = "2.1.0"
        ),
        CrmTicket(
            id = "ticket-010",
            number = 10,
            title = "Большие числа отображаются без разделителей тысяч",
            description = "На устройствах с локалью RU суммы отображаются без пробелов-разделителей тысяч: «1500000» вместо «1 500 000». Проблема в том, что форматирование использует Locale.US вместо Locale.getDefault(). Исправлено в develop, ждёт релиза.",
            priority = TicketPriority.LOW,
            status = TicketStatus.OPEN,
            user = TicketUser("u1", "Иван Петров", "ivan.petrov@example.com"),
            createdAt = "2025-03-16T12:00:00Z",
            tags = listOf("локализация", "форматирование", "числа", "UI"),
            affectedVersion = "2.0.5"
        )
    )
}
