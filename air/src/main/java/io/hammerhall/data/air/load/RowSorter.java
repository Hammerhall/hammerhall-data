package io.hammerhall.data.air.load;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Внешняя сортировка строк таблицы: строки копятся в памяти до {@code runRows}, сортируются и уходят на диск отрезком
 * (deflate, уровень 1); в конце отрезки сливаются в порядке таблицы. Влез один отрезок — диск не нужен. С
 * {@link Spiller} сортировка и запись отрезка идут в его потоке, пока вызывающий читает дальше (одновременно — не больше
 * одного отрезка на всех; порядок и содержимое отрезков от этого не зависят). Строка — значения
 * {@code String}, {@code Long}, {@code Integer}, {@code Boolean}, {@code BigDecimal} или {@code null}. Сортировка
 * устойчива; одинаковых по ключу порядка строк у таблиц выпуска нет (порядок — уникальный ключ).
 */
public final class RowSorter implements Closeable {
    /** Потребитель строк в порядке. */
    public interface Sink {
        void accept(Object[] row) throws IOException;
    }

    /**
     * Поток записи отрезков, общий для нескольких сортировщиков: в работе и в очереди — не больше одного отрезка, иначе
     * вызывающий ждёт (память ограничена). Закрыть после всех {@link #drain}.
     */
    public static final class Spiller implements Closeable {
        private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(
                r -> Thread.ofPlatform().name("air-sort-spill").daemon(true).unstarted(r));
        private final java.util.concurrent.Semaphore slot = new java.util.concurrent.Semaphore(1);

        java.util.concurrent.Future<?> submit(java.util.concurrent.Callable<Void> job) throws IOException {
            try {
                slot.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано", e);
            }
            try {
                return pool.submit(() -> {
                    try {
                        return job.call();
                    } finally {
                        slot.release();
                    }
                });
            } catch (RuntimeException e) {
                slot.release();
                throw e;
            }
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }

    private final String name;
    private final Comparator<Object[]> order;
    private final int runRows;
    private final Path tmpDir;
    private final Spiller spiller;
    private final List<Path> runs = new ArrayList<>();
    private final List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong spilledBytes = new java.util.concurrent.atomic.AtomicLong();
    private List<Object[]> buffer = new ArrayList<>();
    private Path dir;
    private long rows;

    public RowSorter(String name, Comparator<Object[]> order, int runRows, Path tmpDir) {
        this(name, order, runRows, tmpDir, null);
    }

    /** @param spiller поток записи отрезков; {@code null} — сортировать и писать здесь же */
    public RowSorter(String name, Comparator<Object[]> order, int runRows, Path tmpDir, Spiller spiller) {
        this.name = name;
        this.order = order;
        this.runRows = runRows;
        this.tmpDir = tmpDir;
        this.spiller = spiller;
    }

    public void add(Object[] row) throws IOException {
        buffer.add(row);
        rows++;
        if (buffer.size() >= runRows) spill();
    }

    public long rows() {
        return rows;
    }

    /** Отрезков на диске. */
    public int runs() {
        return runs.size();
    }

    /** Байт на диске (сжатых; отрезки, ещё не дописанные, — не в счёт). */
    public long spilledBytes() {
        return spilledBytes.get();
    }

    private void spill() throws IOException {
        if (buffer.isEmpty()) return;
        if (dir == null) {
            dir = tmpDir == null ? Files.createTempDirectory("air-sort-" + name + "-")
                    : Files.createTempDirectory(Files.createDirectories(tmpDir), "air-sort-" + name + "-");
        }
        Path p = dir.resolve("run-" + runs.size());
        runs.add(p);
        List<Object[]> rowsOfRun = buffer;
        buffer = new ArrayList<>();
        if (spiller == null) {
            writeRun(rowsOfRun, p);
        } else {
            pending.add(spiller.submit(() -> {
                writeRun(rowsOfRun, p);
                return null;
            }));
        }
    }

    private void writeRun(List<Object[]> rowsOfRun, Path p) throws IOException {
        rowsOfRun.sort(order);
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new DeflaterOutputStream(Files.newOutputStream(p), def, 1 << 16), 1 << 16))) {
            out.writeInt(rowsOfRun.size());
            for (Object[] r : rowsOfRun) write(out, r);
        } finally {
            def.end();
        }
        spilledBytes.addAndGet(Files.size(p));
    }

    /** Дождаться отрезков, отданных в поток записи. */
    public void await() throws IOException {
        for (java.util.concurrent.Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("прервано", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException("отрезок сортировки " + name, e.getCause());
            }
        }
        pending.clear();
    }

    private static void write(DataOutputStream out, Object[] r) throws IOException {
        out.writeByte(r.length);
        for (Object v : r) {
            switch (v) {
                case null -> out.writeByte(0);
                case String s -> {
                    out.writeByte(1);
                    out.writeUTF(s);
                }
                case Long l -> {
                    out.writeByte(2);
                    out.writeLong(l);
                }
                case Integer i -> {
                    out.writeByte(3);
                    out.writeInt(i);
                }
                case Boolean b -> out.writeByte(b ? 4 : 5);
                case BigDecimal d -> {
                    out.writeByte(6);
                    out.writeUTF(d.toString());
                }
                default -> throw new IllegalArgumentException("тип " + v.getClass().getSimpleName());
            }
        }
    }

    private static Object[] read(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        Object[] r = new Object[n];
        for (int i = 0; i < n; i++) {
            int tag = in.readUnsignedByte();
            r[i] = switch (tag) {
                case 0 -> null;
                case 1 -> in.readUTF();
                case 2 -> in.readLong();
                case 3 -> in.readInt();
                case 4 -> Boolean.TRUE;
                case 5 -> Boolean.FALSE;
                case 6 -> new BigDecimal(in.readUTF());
                default -> throw new IOException("испорченный отрезок сортировки: метка " + tag);
            };
        }
        return r;
    }

    private static final class Run implements Closeable {
        final DataInputStream in;
        final Inflater inf = new Inflater();
        int left;
        Object[] head;

        Run(Path p) throws IOException {
            in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(p), inf, 1 << 16), 1 << 16));
            left = in.readInt();
        }

        boolean next() throws IOException {
            if (left == 0) {
                head = null;
                return false;
            }
            left--;
            head = read(in);
            return true;
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } finally {
                inf.end();
            }
        }
    }

    /** Все строки в порядке таблицы; после этого сортировщик пуст, его файлы удалены. */
    public void drain(Sink sink) throws IOException {
        try {
            if (runs.isEmpty()) {
                buffer.sort(order);
                for (Object[] r : buffer) sink.accept(r);
                buffer = new ArrayList<>();
                return;
            }
            spill();
            await();
            List<Run> open = new ArrayList<>();
            try {
                PriorityQueue<Run> pq = new PriorityQueue<>((a, b) -> order.compare(a.head, b.head));
                for (Path p : runs) {
                    Run r = new Run(p);
                    open.add(r);
                    if (r.next()) pq.add(r);
                }
                while (!pq.isEmpty()) {
                    Run r = pq.poll();
                    sink.accept(r.head);
                    if (r.next()) pq.add(r);
                }
            } catch (EOFException e) {
                throw new IOException("отрезок сортировки " + name + " оборван", e);
            } finally {
                for (Run r : open) r.close();
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() throws IOException {
        buffer = new ArrayList<>();
        for (java.util.concurrent.Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                // закрытие после ошибки: файлы всё равно удаляем
            }
        }
        pending.clear();
        for (Path p : runs) Files.deleteIfExists(p);
        runs.clear();
        if (dir != null) Files.deleteIfExists(dir);
        dir = null;
    }
}
