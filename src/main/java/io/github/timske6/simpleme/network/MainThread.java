package io.github.timske6.simpleme.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.timske6.simpleme.SimpleME;

/**
 * In 1.7.10 packet handlers run on the network thread. Everything that touches the world or a container
 * is queued here and run at the start of the next server/client tick on the main thread.
 */
public final class MainThread {

    private static final Queue<Runnable> SERVER = new ConcurrentLinkedQueue<>();
    private static final Queue<Runnable> CLIENT = new ConcurrentLinkedQueue<>();

    public static void server(Runnable task) {
        SERVER.add(task);
    }

    public static void client(Runnable task) {
        CLIENT.add(task);
    }

    private static void drain(Queue<Runnable> queue) {
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                SimpleME.LOG.error("Error while handling a Simple ME packet", e);
            }
        }
    }

    public static final class ServerTicker {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                drain(SERVER);
            }
        }
    }

    public static final class ClientTicker {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                drain(CLIENT);
            }
        }
    }
}
