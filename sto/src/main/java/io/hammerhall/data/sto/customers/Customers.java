package io.hammerhall.data.sto.customers;

import io.hammerhall.data.core.random.Rng;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Модель клиентов и адресов для следующих модулей ({@code ctx.get(StoKeys.CUSTOMERS)}): ровно то, что записано в
 * {@code customers} и {@code addresses}, плюс скрытые черты, которых в таблицах нет. Хранится колонками в
 * примитивных массивах (≈ 50 байт на клиента и ≈ 20 на адрес); неизменяема после модуля {@code customers}.
 *
 * <h2>Кто и когда покупает</h2>
 * Клиент — {@link #buyer покупатель} или нет (часть регистрируется и не покупает никогда). У покупателя —
 * {@link #firstOrderAt первый заказ} (обычно в день регистрации: регистрация при оформлении), затем повторные
 * заказы, пока он {@link #activeAt активен}: от регистрации до {@link #churnAt ухода}. Удержание — по когортам: кто
 * пришёл на распродаже ({@link #eventCohort}) или по рекламе, уходит раньше; b2b и приложение — держат дольше.
 * <ul>
 *   <li>{@link #firstOrdersOn(LocalDate)} — первые заказы суток (UTC) по моменту;</li>
 *   <li>{@link #sampler(LocalDate)} — выбор клиента для повторного заказа в эти сутки: среди активных, сделавших
 *       первый заказ раньше, с весом {@link #activity} (ожидаемых повторных заказов за 30 дней активности; сезон и
 *       события календаря — множитель модуля заказов). Построение — O(числа клиентов), раз в сутки.</li>
 * </ul>
 * Черты: {@link #priceSensitivity}, {@link #promoSensitivity} (0–1), {@link #favoriteDepartments} (отделы
 * каталога), {@link #device} (устройство для сессий), {@link #pickupShare} (доля заказов в пункт выдачи),
 * {@link #basketScale} (множитель корзины: у b2b — крупные редкие заказы).
 *
 * <h2>Где живёт</h2>
 * {@link #cityId} — город регистрации (как в таблице); часть клиентов переезжает — {@link #cityAt}.
 * {@link #addressAt(int, long, Rng)} — адрес доставки на момент: из адресов, добавленных к моменту, в городе
 * клиента на этот момент, по виду (дом чаще работы); {@code -1} — адресов нет (только пункт выдачи).
 *
 * <h2>Заложенное нарушение</h2>
 * Группы адресов с одной точкой у разных клиентов ({@link #sharedGroup}): несколько аккаунтов на один адрес
 * (перепродажа, злоупотребление скидками новичка). Ключи — в {@code ctx.planted()} под кодом
 * {@code shared_address}; заказы этих клиентов идут на этот адрес ({@link #addressAt} выбирает его почти всегда).
 *
 * <p>Моменты — секунды Unix; хранятся в {@code int} (до 2038 года). id клиента и адреса — с 1, по времени появления.
 */
public final class Customers {
    public static final String[] ACQUIRED = {"organic", "search", "ads", "social", "referral", "offline"};
    public static final String[] DEVICES = {"desktop", "mobile_web", "tablet", "app"};
    public static final String[] ADDRESS_KINDS = {"home", "work", "other"};

    static final byte B2B = 1, EVENT = 2, ONE_TIMER = 4, RING = 8, BUYER = 16;
    /** «Не уходит в горизонте плана». */
    static final int NEVER = Integer.MAX_VALUE;

    // ---------------------------------------------------------------- клиенты (индекс = id − 1)
    int n;
    int[] registeredAt, churnAt, firstOrderAt, moveAt, companyRef;
    short[] city, moveCity, birthYear, firstName, surname, favDepts;
    byte[] flags, gender, acquired, device, priceSens, promoSens, pickup;
    float[] activity, basket;
    final List<String> companies = new ArrayList<>();
    /** Отделы каталога: индекс → id категории. */
    final int[] deptIds;

    // ---------------------------------------------------------------- адреса (индекс = id − 1)
    int na;
    int[] aCustomer, aLat, aLon, aCreated;
    short[] aCity;
    byte[] aKind;
    private int[] custAddrOffset, custAddr;
    private int[] sharedAddr, sharedGroupOf;
    private int sharedGroups;
    private long[] byFirstOrder;

    Customers(int capacity, int[] deptIds) {
        this.deptIds = deptIds.clone();
        alloc(capacity);
    }

    private void alloc(int cap) {
        registeredAt = Arrays.copyOf(registeredAt == null ? new int[0] : registeredAt, cap);
        churnAt = Arrays.copyOf(churnAt == null ? new int[0] : churnAt, cap);
        firstOrderAt = Arrays.copyOf(firstOrderAt == null ? new int[0] : firstOrderAt, cap);
        moveAt = Arrays.copyOf(moveAt == null ? new int[0] : moveAt, cap);
        companyRef = Arrays.copyOf(companyRef == null ? new int[0] : companyRef, cap);
        city = Arrays.copyOf(city == null ? new short[0] : city, cap);
        moveCity = Arrays.copyOf(moveCity == null ? new short[0] : moveCity, cap);
        birthYear = Arrays.copyOf(birthYear == null ? new short[0] : birthYear, cap);
        firstName = Arrays.copyOf(firstName == null ? new short[0] : firstName, cap);
        surname = Arrays.copyOf(surname == null ? new short[0] : surname, cap);
        favDepts = Arrays.copyOf(favDepts == null ? new short[0] : favDepts, cap);
        flags = Arrays.copyOf(flags == null ? new byte[0] : flags, cap);
        gender = Arrays.copyOf(gender == null ? new byte[0] : gender, cap);
        acquired = Arrays.copyOf(acquired == null ? new byte[0] : acquired, cap);
        device = Arrays.copyOf(device == null ? new byte[0] : device, cap);
        priceSens = Arrays.copyOf(priceSens == null ? new byte[0] : priceSens, cap);
        promoSens = Arrays.copyOf(promoSens == null ? new byte[0] : promoSens, cap);
        pickup = Arrays.copyOf(pickup == null ? new byte[0] : pickup, cap);
        activity = Arrays.copyOf(activity == null ? new float[0] : activity, cap);
        basket = Arrays.copyOf(basket == null ? new float[0] : basket, cap);
    }

    /** Ещё место под клиентов (рост в полтора раза). */
    void ensure(int need) {
        if (need > registeredAt.length) alloc(Math.max(need, registeredAt.length + (registeredAt.length >> 1) + 1024));
    }

    /** Адреса уже упорядочены по времени (индекс = id − 1): подрезать массивы клиентов, собрать указатели. */
    void seal(int[] aCustomer, short[] aCity, int[] aLat, int[] aLon, byte[] aKind, int[] aCreated, int[] sharedAddr,
              int[] sharedGroupOf) {
        if (registeredAt.length != n) alloc(n);
        this.na = aCustomer.length;
        this.aCustomer = aCustomer;
        this.aCity = aCity;
        this.aLat = aLat;
        this.aLon = aLon;
        this.aKind = aKind;
        this.aCreated = aCreated;
        // custAddrOffset[id − 1] … custAddrOffset[id] — адреса клиента id в custAddr (по id адреса = по времени)
        custAddrOffset = new int[n + 1];
        for (int c : aCustomer) custAddrOffset[c]++;
        for (int i = 1; i <= n; i++) custAddrOffset[i] += custAddrOffset[i - 1];
        custAddr = new int[na];
        int[] fill = new int[n + 1];
        System.arraycopy(custAddrOffset, 0, fill, 1, n);
        for (int i = 0; i < na; i++) custAddr[fill[aCustomer[i]]++] = i + 1;
        this.sharedAddr = sharedAddr;
        this.sharedGroupOf = sharedGroupOf;
        int g = -1;
        for (int x : sharedGroupOf) g = Math.max(g, x);
        this.sharedGroups = g + 1;
        long[] k = new long[n];
        int m = 0;
        for (int i = 0; i < n; i++) if (firstOrderAt[i] >= 0) k[m++] = ((long) firstOrderAt[i] << 32) | (i + 1);
        byFirstOrder = Arrays.copyOf(k, m);
        Arrays.sort(byFirstOrder);
    }

    // ================================================================ клиенты: поля таблицы

    public int size() {
        return n;
    }

    public long registeredAt(int id) {
        return registeredAt[id - 1];
    }

    /** Город регистрации — {@code customers.city_id}. */
    public int cityId(int id) {
        return city[id - 1];
    }

    public boolean female(int id) {
        return gender[id - 1] == 1;
    }

    /** «f» / «m». */
    public String gender(int id) {
        return female(id) ? "f" : "m";
    }

    public String firstName(int id) {
        return PersonNames.first(female(id), firstName[id - 1]);
    }

    public String lastName(int id) {
        return PersonNames.surname(surname[id - 1], female(id));
    }

    public int birthYear(int id) {
        return birthYear[id - 1];
    }

    /** Полных лет на дату (по году: без дня рождения в данных). */
    public int age(int id, LocalDate d) {
        return d.getYear() - birthYear[id - 1];
    }

    public String acquiredVia(int id) {
        return ACQUIRED[acquired[id - 1]];
    }

    public boolean b2b(int id) {
        return (flags[id - 1] & B2B) != 0;
    }

    /** Организация b2b-клиента; {@code null} — частное лицо. */
    public String companyName(int id) {
        int k = companyRef[id - 1];
        return k < 0 ? null : companies.get(k);
    }

    // ================================================================ клиенты: скрытые черты

    /** Покупает ли вообще (часть клиентов регистрируется и не покупает). */
    public boolean buyer(int id) {
        return (flags[id - 1] & BUYER) != 0;
    }

    /** Момент первого заказа; −1 — не покупатель. Может быть до начала периода (старые клиенты). */
    public long firstOrderAt(int id) {
        return firstOrderAt[id - 1];
    }

    /** Момент ухода (после него клиент не заказывает); {@link Long#MAX_VALUE} — не уходит в горизонте. */
    public long churnAt(int id) {
        int c = churnAt[id - 1];
        return c == NEVER ? Long.MAX_VALUE : c;
    }

    /** Активен в момент: покупатель, зарегистрирован и ещё не ушёл. */
    public boolean activeAt(int id, long t) {
        int i = id - 1;
        return (flags[i] & BUYER) != 0 && t >= registeredAt[i] && (churnAt[i] == NEVER || t < churnAt[i]);
    }

    /** Активен хотя бы момент в сутках (UTC). */
    public boolean activeOn(int id, LocalDate day) {
        long a = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        int i = id - 1;
        return (flags[i] & BUYER) != 0 && registeredAt[i] < a + 86_400 && (churnAt[i] == NEVER || churnAt[i] > a);
    }

    /** Ожидаемых повторных заказов за 30 дней активности (тяжёлый хвост; у b2b — реже). */
    public double activity(int id) {
        return activity[id - 1];
    }

    /** Чувствительность к цене, 0–1 (0,5 — средняя). */
    public double priceSensitivity(int id) {
        return (priceSens[id - 1] & 0xff) / 100.0;
    }

    /** Чувствительность к акциям и промокодам, 0–1. */
    public double promoSensitivity(int id) {
        return (promoSens[id - 1] & 0xff) / 100.0;
    }

    /** Любимые отделы каталога (id категорий 2-го уровня), 1–3, по убыванию предпочтения. */
    public int[] favoriteDepartments(int id) {
        int v = favDepts[id - 1] & 0xffff;
        int k = v >>> 12;
        int[] out = new int[k];
        for (int j = 0; j < k; j++) out[j] = deptIds[(v >>> (4 * j)) & 0xf];
        return out;
    }

    /** Устройство, с которого обычно заходит: {@code desktop | mobile_web | tablet | app}. */
    public String device(int id) {
        return DEVICES[device[id - 1]];
    }

    /** Доля заказов в пункт выдачи (остальное — курьер или почта, если есть адрес). */
    public double pickupShare(int id) {
        return (pickup[id - 1] & 0xff) / 100.0;
    }

    /** Множитель размера корзины (число строк и штук): ≈ 1 у частных, 2–25 у b2b. */
    public double basketScale(int id) {
        return basket[id - 1];
    }

    /** Пришёл на «11.11» или «чёрной пятнице» (такие когорты удерживаются хуже). */
    public boolean eventCohort(int id) {
        return (flags[id - 1] & EVENT) != 0;
    }

    /** Заложенный «одноразовый» клиент: уходит в первые месяцы. */
    public boolean oneTimer(int id) {
        return (flags[id - 1] & ONE_TIMER) != 0;
    }

    /** Момент переезда; −1 — не переезжает. */
    public long movedAt(int id) {
        return moveAt[id - 1];
    }

    /** Город клиента на момент (после переезда — новый). */
    public int cityAt(int id, long t) {
        int i = id - 1;
        return moveAt[i] >= 0 && t >= moveAt[i] ? moveCity[i] : city[i];
    }

    // ================================================================ кто заказывает в сутки

    /** Наибольший id клиента, зарегистрированного раньше момента {@code t} (0 — никого). */
    public int lastRegisteredBefore(long t) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (registeredAt[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Покупатели, чей первый заказ приходится на сутки (UTC), — по моменту первого заказа. */
    public int[] firstOrdersOn(LocalDate day) {
        long a = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), b = a + 86_400;
        int lo = lower(a << 32), hi = lower(b << 32);
        int[] out = new int[hi - lo];
        for (int i = lo; i < hi; i++) out[i - lo] = (int) byFirstOrder[i];
        return out;
    }

    private int lower(long key) {
        int i = Arrays.binarySearch(byFirstOrder, key);
        return i < 0 ? -i - 1 : i;
    }

    /** Выбор клиента для повторного заказа в сутки: активные, с первым заказом раньше суток, вес — {@link #activity}. */
    public Sampler sampler(LocalDate day) {
        long a = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        int last = lastRegisteredBefore(a);
        int[] ids = new int[last];
        double[] cum = new double[last];
        int m = 0;
        double c = 0;
        for (int i = 0; i < last; i++) {
            if ((flags[i] & BUYER) == 0 || firstOrderAt[i] >= a || (churnAt[i] != NEVER && churnAt[i] <= a)) continue;
            c += activity[i];
            ids[m] = i + 1;
            cum[m++] = c;
        }
        return new Sampler(Arrays.copyOf(ids, m), Arrays.copyOf(cum, m));
    }

    /** Взвешенный выбор клиента (накопленные веса, двоичный поиск). */
    public static final class Sampler {
        private final int[] ids;
        private final double[] cum;

        Sampler(int[] ids, double[] cum) {
            this.ids = ids;
            this.cum = cum;
        }

        public int size() {
            return ids.length;
        }

        /** Σ {@link #activity} — ожидаемых повторных заказов за 30 дней от всех активных. */
        public double totalActivity() {
            return cum.length == 0 ? 0 : cum[cum.length - 1];
        }

        public int[] ids() {
            return ids.clone();
        }

        /** id клиента; {@code -1} — никого. */
        public int pick(Rng r) {
            if (ids.length == 0) return -1;
            double x = r.nextDouble() * cum[cum.length - 1];
            int i = Arrays.binarySearch(cum, x);
            if (i < 0) i = -i - 1;
            return ids[Math.min(i, ids.length - 1)];
        }
    }

    // ================================================================ адреса

    public int addressCount() {
        return na;
    }

    /** Адреса клиента по времени добавления. */
    public int[] addressesOf(int id) {
        return Arrays.copyOfRange(custAddr, custAddrOffset[id - 1], custAddrOffset[id]);
    }

    public int addressCustomer(int aid) {
        return aCustomer[aid - 1];
    }

    public int addressCity(int aid) {
        return aCity[aid - 1];
    }

    public double addressLat(int aid) {
        return aLat[aid - 1] / 1e6;
    }

    public double addressLon(int aid) {
        return aLon[aid - 1] / 1e6;
    }

    /** {@code home | work | other}. */
    public String addressKind(int aid) {
        return ADDRESS_KINDS[aKind[aid - 1]];
    }

    public long addressCreatedAt(int aid) {
        return aCreated[aid - 1];
    }

    /** Группа адресов с одной точкой у разных клиентов (заложенное нарушение); −1 — обычный адрес. */
    public int sharedGroup(int aid) {
        int i = Arrays.binarySearch(sharedAddr, aid);
        return i < 0 ? -1 : sharedGroupOf[i];
    }

    /** Число заложенных групп (в окне могут быть не все: часть — после конца периода). */
    public int sharedGroups() {
        return sharedGroups;
    }

    private static final double[] KIND_W = {6, 3, 1}, KIND_W_B2B = {1, 8, 1};

    /**
     * Адрес доставки на момент {@code t}: из добавленных к моменту, в городе клиента на момент (если таких нет — любой);
     * вес вида — дом 6, работа 3, другое 1 (у b2b — работа 8); адрес из группы с общей точкой — почти всегда он.
     * {@code -1} — адресов нет (доставка только в пункт выдачи).
     */
    public int addressAt(int id, long t, Rng r) {
        int from = custAddrOffset[id - 1], to = custAddrOffset[id];
        int cityNow = cityAt(id, t);
        double[] kw = b2b(id) ? KIND_W_B2B : KIND_W;
        double total = 0, totalAny = 0;
        for (int k = from; k < to; k++) {
            int a = custAddr[k] - 1;
            if (aCreated[a] > t) break;
            double w = kw[aKind[a]] * (sharedGroup(a + 1) >= 0 ? 30 : 1);
            totalAny += w;
            if (aCity[a] == cityNow) total += w;
        }
        if (totalAny == 0) return -1;
        boolean anyCity = total == 0;
        double x = r.nextDouble() * (anyCity ? totalAny : total);
        int last = -1;
        for (int k = from; k < to; k++) {
            int a = custAddr[k] - 1;
            if (aCreated[a] > t) break;
            if (!anyCity && aCity[a] != cityNow) continue;
            last = a + 1;
            x -= kw[aKind[a]] * (sharedGroup(a + 1) >= 0 ? 30 : 1);
            if (x < 0) return a + 1;
        }
        return last;
    }
}
