package io.hammerhall.data.auc.site;

import java.util.Arrays;

/**
 * Что модулю сайта нужно от мира — компактно, массивами по полям (индекс — {@code id} выпуска; 0 не используется,
 * пустое — 0). Заполняет переходник от записей мира ({@code CLIENTS}, {@code CALENDAR}, {@code AUCTION},
 * {@code MONEY}); затем {@link #finish()} строит производные указатели.
 *
 * <p>Время — секунды Unix (UTC). <b>Факты</b> (ставки, поручения, итоги, счета, платежи) — только раньше среза
 * {@link #cutoff}. <b>Смоделированное</b> — участники лота ({@link #pLot}) и интерес к лоту ({@link #lPop}) — не
 * зависит от среза: так мир даёт их и для лотов, открытых на срезе (горизонт моделирования), и визиты
 * до прежнего среза при продлении не меняются.
 */
public final class SiteInput {
    // ---------------------------------------------------------------- флаги
    /** Клиент согласен на аналитику — его визиты в журнале. */
    public static final byte C_ANALYTICS = 1;
    /** Согласие на рассылки (без него — только служебные уведомления). */
    public static final byte C_MARKETING = 2;
    /** Есть приложение: устройство {@code app} и разрешены push. */
    public static final byte C_PUSH = 4;

    public static final byte F_LIVE = 0, F_TIMED = 1;
    public static final byte DEV_DESKTOP = 0, DEV_MOBILE = 1, DEV_APP = 2;
    public static final byte VIA_SITE = 0, VIA_APP = 1;

    /** Ставка: принята. */
    public static final short B_ACCEPTED = 1;
    /** Ручная онлайн-ставка ({@code channel = online, kind = manual}) — форма ставки на сайте или в приложении. */
    public static final short B_MANUAL = 2;
    /** Повтор запроса (двойное нажатие): отклонена как {@code duplicate}. */
    public static final short B_REPEAT = 4;
    /** Мимо формы: сумма не на сетке через API ({@code off_increment}) — события {@code bid_submit} нет. */
    public static final short B_NO_FORM = 8;
    /** Через партнёрскую площадку — не наш сайт, события нет. */
    public static final short B_PARTNER = 16;
    /** Ставка в пользу продавца (нет участника). */
    public static final short B_VENDOR = 32;
    /** Канал {@code online}. */
    public static final short B_ONLINE = 64;

    /** Регистрация: решение принял сотрудник (живые торги) — уведомление о решении. */
    public static final byte R_MANUAL = 1;
    public static final byte R_REJECTED = 2;
    /** Делал принятые ставки. */
    public static final byte R_BIDS = 4;
    /** Выиграл хотя бы один лот. */
    public static final byte R_WON = 8;

    // ---------------------------------------------------------------- срез и масштаб
    /** Срез фактов, секунды (всё в выпуске — строго раньше). */
    public long cutoff;
    /** Первые сутки сайта (UTC, день эпохи) — начало периода. */
    public long firstDay;
    /** Масштаб профиля (доля живого потока от {@code full}). */
    public double scale;

    // ---------------------------------------------------------------- города
    /** Смещение местного времени от UTC, секунды, по {@code city_id} (летнего времени нет). */
    public int[] cityOffset = new int[0];
    /** Города анонимных посетителей и их веса (население). */
    public int[] cityIds = new int[0];
    public double[] cityWeight = new double[0];

    // ---------------------------------------------------------------- клиенты: индекс — client_id
    public int nClients;
    /** Естественный ключ клиента — для случайности. */
    public long[] cKey;
    public int[] cCity;
    public long[] cRegAt;
    public byte[] cFlags;
    /** Визитов «посмотреть» в сутки (скрытая черта: частота торгов, интерес). */
    public float[] cVisits;
    /** Любимый отдел (0–5). */
    public byte[] cDept;
    /** Устройства клиента: {@code [cDevFrom[c], cDevFrom[c + 1])} в {@link #devKey}, {@link #devKind}. */
    public int[] cDevFrom;
    public long[] devKey;
    public byte[] devKind;

    // ---------------------------------------------------------------- торги: индекс — sale_id
    public int nSales;
    public byte[] sFormat;
    public byte[] sDept;
    /** Каталог опубликован (0 — нет). */
    public long[] sPub;
    public long[] sOpens;
    public long[] sStart;
    /** Закрылся последний лот (0 — не завершены к срезу). */
    public long[] sEnd;

    // ---------------------------------------------------------------- лоты: индекс — lot_id
    public int nLots;
    public long[] lKey;
    public int[] lSale;
    public int[] lNo;
    public long[] lPub;
    /** План: онлайн — плановое закрытие (волна), живые — начало слота (по темпу торгов). */
    public long[] lPlan;
    /** Закрыт (0 — не закрыт к срезу). */
    public long[] lClosed;
    /** Снят (0 — нет). */
    public long[] lWithdrawn;
    /** Продан — покупатель (client_id; 0 — не продан). */
    public int[] lBuyer;
    /** Интерес к лоту — смоделированное число участников (не зависит от среза). */
    public float[] lPop;
    /** Категория (для поисковых запросов и числа найденного). */
    public int[] lCat;
    /** Мастер (0 — нет). */
    public int[] lMaker;

    // ---------------------------------------------------------------- тексты запросов
    /** Название категории по-русски, по {@code category_id}. */
    public String[] catName = new String[0];
    /** Мастер: имя для запроса (фамилия, название мастерской), по {@code maker_id}. */
    public String[] makerName = new String[0];

    // ---------------------------------------------------------------- ставки (факты), по id (= по времени)
    public int nBids;
    /** {@code bids.id}; {@code null} — по порядку с 1 ({@code id = индекс + 1}; так выдаёт мир торгов — память full). */
    public long[] bId;
    public int[] bLot;
    /** Клиент участника (0 — в пользу продавца). */
    public int[] bClient;
    /** Момент, секунды Unix ({@code int} — до 2038 г.). */
    public int[] bAt;
    public short[] bMs;
    public short[] bFlags;

    // ---------------------------------------------------------------- поручения-максимумы онлайн с сайта или приложения
    public int nOrders;
    public int[] oId;
    public long[] oAt;
    public int[] oLot;
    public int[] oClient;
    public byte[] oVia;

    // ---------------------------------------------------------------- участники лотов (смоделированные), по лоту
    public int nPart;
    public int[] pLot;
    public int[] pClient;
    /** Первая ставка участника на лоте (может быть после среза). */
    public long[] pFirstAt;
    /** Ставит онлайн (иначе — зал, телефон, заочно). */
    public boolean[] pOnline;

    // ---------------------------------------------------------------- регистрации (факты)
    public int nRegs;
    public int[] rId;
    public int[] rClient;
    public int[] rSale;
    /** Решение (0 — нет к срезу). */
    public long[] rDecided;
    public byte[] rFlags;

    // ---------------------------------------------------------------- деньги (факты)
    public int nInv;
    public int[] iId;
    public int[] iClient;
    public int[] iSale;
    public long[] iIssued;
    public long[] iDue;
    /** Полная оплата (0 — нет к срезу). */
    public long[] iPaid;
    /** Отмена (0 — нет). */
    public long[] iCancelled;
    /** Успешные платежи по счетам: момент, счёт, клиент, id платежа. */
    public int nPay;
    public long[] payAt;
    public int[] payInvoice;
    public int[] payClient;
    public int[] payId;
    /** Возвраты депозитов. */
    public int nRefund;
    public long[] refAt;
    public int[] refClient;
    public int[] refSale;
    public int[] refId;
    /** Отчёты комитентам. */
    public int nSet;
    public int[] stId;
    public int[] stClient;
    public int[] stSale;
    public long[] stCreated;
    /** Выплаты комитентам: момент, отчёт, клиент, торги, id платежа. */
    public int nPayout;
    public long[] poAt;
    public int[] poSettlement;
    public int[] poClient;
    public int[] poSale;
    public int[] poId;

    // ---------------------------------------------------------------- источники уведомлений
    /**
     * События-источники уведомлений (кроме {@code closing_soon} — его модуль строит по наблюдению), по моменту:
     * вид ({@code Rules.N_*}), адресат, торги, лот, счёт и числа ключа события ({@code event_key} =
     * {@code <вид>:<keyA>[:<keyB>]}, {@code keyB} = −1 — нет). Заполняет переходник мира ({@code AuctionWorld.notices},
     * {@code MoneyWorld.events}); без мира торгов — {@link #finish(java.util.function.Consumer)} по готовому входу.
     */
    public int nSrc;
    /** Момент, секунды Unix ({@code int}). */
    public int[] srcAt;
    public byte[] srcKind;
    public int[] srcClient, srcSale, srcLot, srcInvoice;
    public int[] srcKeyA, srcKeyB;

    // ================================================================ производное (finish)
    /** Участники лота: {@code [partFrom[l], partFrom[l + 1])}. */
    int[] partFrom;
    /** Лоты торгов по номеру: {@code saleLots[saleFrom[s] .. saleFrom[s + 1])}. */
    int[] saleFrom, saleLots;
    /** Индекс лота в списке его торгов (для страниц каталога). */
    int[] lotPos;
    /** Согласные на аналитику клиенты по дате регистрации. */
    int[] consenting;
    long[] consentingReg;
    /** Клиенты по любимому отделу, по дате регистрации: {@code deptPool[d]}, {@code deptPoolReg[d]}. */
    int[][] deptPool;
    long[][] deptPoolReg;
    /** Ручные онлайн-ставки с формы: индексы в ставках (по времени). */
    int[] manual;
    int[] manualAt;
    /** Онлайн-лоты по плановому закрытию. */
    int[] timedByPlan;
    long[] timedPlan;
    /** Первое приложение и первое неприложение клиента (индекс в устройствах; −1 — нет). */
    int[] appDev, webDev;

    static long day(long t) {
        return Math.floorDiv(t, 86_400L);
    }

    /** Построить производные указатели; массивы входа после этого не меняются. */
    public SiteInput finish() {
        return finish(null);
    }

    /**
     * То же; {@code sources} строит источники уведомлений по готовым указателям, если вход их не дал (вход модуля без
     * мира торгов — в тестах модуля); без них и без {@code sources} — ошибка.
     */
    SiteInput finish(java.util.function.Consumer<SiteInput> sources) {
        // участники — по лоту (вход упорядочен по лоту)
        partFrom = new int[nLots + 2];
        for (int i = 0; i < nPart; i++) {
            if (i > 0 && pLot[i] < pLot[i - 1]) throw new IllegalStateException("участники не по лоту");
            partFrom[pLot[i] + 1]++;
        }
        for (int l = 1; l < partFrom.length; l++) partFrom[l] += partFrom[l - 1];

        // лоты торгов по номеру
        saleFrom = new int[nSales + 2];
        for (int l = 1; l <= nLots; l++) saleFrom[lSale[l] + 1]++;
        for (int s = 1; s < saleFrom.length; s++) saleFrom[s] += saleFrom[s - 1];
        saleLots = new int[nLots];
        long[] k = new long[nLots];
        int[] v = new int[nLots];
        for (int l = 1; l <= nLots; l++) {
            k[l - 1] = ((long) lSale[l] << 42) | ((long) lNo[l] << 22) | l;
            v[l - 1] = l;
        }
        Radix.sort(k, v, nLots, 64);
        System.arraycopy(v, 0, saleLots, 0, nLots);
        lotPos = new int[nLots + 1];
        for (int s = 1; s <= nSales; s++) for (int i = saleFrom[s]; i < saleFrom[s + 1]; i++) lotPos[saleLots[i]] = i - saleFrom[s];

        // клиенты: согласные, по отделам — по дате регистрации
        int nc = 0;
        for (int c = 1; c <= nClients; c++) if ((cFlags[c] & C_ANALYTICS) != 0) nc++;
        consenting = new int[nc];
        k = new long[nClients];
        v = new int[nClients];
        nc = 0;
        for (int c = 1; c <= nClients; c++) {
            if ((cFlags[c] & C_ANALYTICS) == 0) continue;
            k[nc] = cRegAt[c] - Long.MIN_VALUE;
            v[nc++] = c;
        }
        Radix.sort(k, v, nc, 64);
        System.arraycopy(v, 0, consenting, 0, nc);
        consentingReg = new long[nc];
        for (int i = 0; i < nc; i++) consentingReg[i] = cRegAt[consenting[i]];
        deptPool = new int[6][];
        deptPoolReg = new long[6][];
        for (int d = 0; d < 6; d++) {
            int n = 0;
            for (int c = 1; c <= nClients; c++) {
                if (cDept[c] != d) continue;
                k[n] = cRegAt[c] - Long.MIN_VALUE;
                v[n++] = c;
            }
            Radix.sort(k, v, n, 64);
            deptPool[d] = Arrays.copyOf(v, n);
            deptPoolReg[d] = new long[n];
            for (int i = 0; i < n; i++) deptPoolReg[d][i] = cRegAt[deptPool[d][i]];
        }
        appDev = new int[nClients + 1];
        webDev = new int[nClients + 1];
        for (int c = 1; c <= nClients; c++) {
            appDev[c] = webDev[c] = -1;
            for (int j = cDevFrom[c]; j < cDevFrom[c + 1]; j++) {
                if (devKind[j] == DEV_APP) {
                    if (appDev[c] < 0) appDev[c] = j;
                } else if (webDev[c] < 0) webDev[c] = j;
            }
        }

        // ручные онлайн-ставки с формы
        int nm = 0;
        for (int i = 0; i < nBids; i++) if (formBid(i)) nm++;
        manual = new int[nm];
        manualAt = new int[nm];
        nm = 0;
        for (int i = 0; i < nBids; i++) {
            if (i > 0 && bId != null && bId[i] <= bId[i - 1]) throw new IllegalStateException("ставки не по id");
            if (!formBid(i)) continue;
            manual[nm] = i;
            manualAt[nm++] = bAt[i];
        }

        // онлайн-лоты по плану
        int nt = 0;
        for (int l = 1; l <= nLots; l++) if (sFormat[lSale[l]] == F_TIMED) nt++;
        timedPlan = new long[nt];
        timedByPlan = new int[nt];
        nt = 0;
        for (int l = 1; l <= nLots; l++) {
            if (sFormat[lSale[l]] != F_TIMED) continue;
            timedPlan[nt] = lPlan[l];
            timedByPlan[nt++] = l;
        }
        Radix.sort(timedPlan, timedByPlan, nt, 40);

        if (srcAt == null) {
            if (sources == null) throw new IllegalStateException("нет источников уведомлений: в мире нет торгов и денег");
            sources.accept(this);
        }
        for (int i = 1; i < nSrc; i++) if (srcAt[i] < srcAt[i - 1]) throw new IllegalStateException("источники уведомлений не по времени");
        return this;
    }

    /** {@code bids.id} ставки с индексом {@code i}. */
    long bidId(int i) {
        return bId == null ? i + 1L : bId[i];
    }

    /** Индекс ставки по {@code bids.id} — как {@code Arrays.binarySearch(bId, 0, nBids, id)}: нет — отрицательное. */
    int bidIndex(long id) {
        if (bId != null) return Arrays.binarySearch(bId, 0, nBids, id);
        if (id >= 1 && id <= nBids) return (int) (id - 1);
        return id < 1 ? -1 : -(nBids + 1);
    }

    /** Ручная онлайн-ставка, поданная формой сайта или приложения. */
    boolean formBid(int i) {
        short f = bFlags[i];
        return (f & B_MANUAL) != 0 && (f & (B_NO_FORM | B_PARTNER | B_VENDOR)) == 0 && bClient[i] != 0;
    }

    /** Местное время клиента: секунды от полуночи. */
    int localSecond(int client, long t) {
        int off = cityOffset.length > cCity[client] ? cityOffset[cCity[client]] : 3 * 3600;
        return (int) Math.floorMod(t + off, 86_400L);
    }

    int offsetOfCity(int city) {
        return city > 0 && city < cityOffset.length ? cityOffset[city] : 3 * 3600;
    }
}
