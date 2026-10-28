package com.aurora.freecesswhitelist;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 极简的“延迟执行”后台线程。
 *
 * 为什么要延迟：
 * 1) 类加载回调（ClassLoader.loadClass 的 after hook）里直接调用 XposedBridge.hookMethod
 *    存在风险 —— 此时可能还处在 ART 的类加载流程中，挂钩会触发线程挂起/重入，
 *    严重时会导致 system_server 卡死。把挂钩动作丢到独立线程、稍后再做，只需几毫秒，
 *    而 Freecess 的冻结决策远在这之后，完全来得及。
 * 2) UidMap 需要读 /data/system/packages.list，属于磁盘 IO，绝不能放在冻结回调里做。
 *
 * 线程是 daemon 的，随 system_server 一起退出，不会阻止关机。
 */
public final class Worker {

    private static final BlockingQueue<Runnable> QUEUE = new LinkedBlockingQueue<>();

    private static volatile boolean sStarted;

    private Worker() {
    }

    public static void submit(Runnable task) {
        if (task == null) return;
        try {
            QUEUE.offer(task);
            ensureStarted();
        } catch (Throwable ignored) {
        }
    }

    private static void ensureStarted() {
        if (sStarted) return;
        synchronized (Worker.class) {
            if (sStarted) return;
            sStarted = true;
            try {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        loop();
                    }
                }, "freecess-wl-worker");
                t.setDaemon(true);
                t.start();
                XLog.i("Worker 线程已启动");
            } catch (Throwable t) {
                XLog.e("Worker 启动失败", t);
            }
        }
    }

    private static void loop() {
        while (true) {
            try {
                Runnable task = QUEUE.take();
                // 让出一点时间，确保触发这次任务的类加载流程已经走完
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ignored) {
                }
                try {
                    task.run();
                } catch (Throwable t) {
                    XLog.e("Worker 任务异常（已吞掉）", t);
                }
            } catch (Throwable ignored) {
                // 永不退出循环
            }
        }
    }
}
