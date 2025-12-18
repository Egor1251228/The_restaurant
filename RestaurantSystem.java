import java.util.concurrent.*;
import java.util.*;

class Order {
    private static int counter = 1;
    private final int id;
    private final String dishName;
    private final int preparationTime; // в секундах
    private OrderStatus status;
    private String waiterName;

    enum OrderStatus {
        CREATED, COOKING, READY, DELIVERED
    }

    public Order(String dishName, int preparationTime) {
        this.id = counter++;
        this.dishName = dishName;
        this.preparationTime = preparationTime;
        this.status = OrderStatus.CREATED;
    }

    public int getId() { return id; }
    public String getDishName() { return dishName; }
    public int getPreparationTime() { return preparationTime; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getWaiterName() { return waiterName; }
    public void setWaiterName(String waiterName) { this.waiterName = waiterName; }

    @Override
    public String toString() {
        return "Order #" + id + " [" + dishName + "], prep time: " + preparationTime + "s";
    }
}

class CookingTask implements Runnable {
    private final Order order;
    private final BlockingQueue<Order> readyOrders;

    public CookingTask(Order order, BlockingQueue<Order> readyOrders) {
        this.order = order;
        this.readyOrders = readyOrders;
    }

    @Override
    public void run() {
        try {
            order.setStatus(Order.OrderStatus.COOKING);
            log("Начал готовить " + order.getDishName());

            Thread.sleep(order.getPreparationTime() * 1000L);

            order.setStatus(Order.OrderStatus.READY);
            log("Приготовил " + order.getDishName());

            readyOrders.put(order);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String message) {
        System.out.println("[Повар " + Thread.currentThread().getName() + "] " + message);
    }
}

class Waiter implements Runnable {
    private final String name;
    private final BlockingQueue<Order> kitchenQueue;
    private final BlockingQueue<Order> readyOrders;
    private volatile boolean working = true;

    public Waiter(String name, BlockingQueue<Order> kitchenQueue, BlockingQueue<Order> readyOrders) {
        this.name = name;
        this.kitchenQueue = kitchenQueue;
        this.readyOrders = readyOrders;
    }

    public void stop() {
        working = false;
    }

    @Override
    public void run() {
        log("Начал работу");

        while (working || !kitchenQueue.isEmpty()) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(500, 2000));
                Order order = generateOrder();
                order.setWaiterName(name);

                log("Принял " + order);

                log("Отправляет на кухню: " + order.getDishName());
                kitchenQueue.put(order);

                log("Ждет готовности заказа #" + order.getId());
                Order readyOrder = readyOrders.take();

                if (readyOrder.getId() == order.getId()) {
                    // Доставляем блюдо клиенту
                    Thread.sleep(500); // время доставки
                    readyOrder.setStatus(Order.OrderStatus.DELIVERED);
                    log("Доставил " + readyOrder.getDishName() + " клиенту");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log("Закончил работу");
    }

    private Order generateOrder() {
        String[] dishes = {"Пицца", "Паста", "Стейк", "Салат", "Суп", "Бургер", "Рыба", "Десерт"};
        String dish = dishes[ThreadLocalRandom.current().nextInt(dishes.length)];
        int prepTime = ThreadLocalRandom.current().nextInt(2, 8); // 2-7 секунд
        return new Order(dish, prepTime);
    }

    private void log(String message) {
        System.out.println("[Официант " + name + "] " + message);
    }
}

class Restaurant {
    private final BlockingQueue<Order> kitchenQueue;
    private final BlockingQueue<Order> readyOrders;
    private final ExecutorService kitchen;
    private final List<Waiter> waiters;
    private final List<Thread> waiterThreads;
    private volatile boolean running = false;

    public Restaurant(int kitchenSize, int waiterCount) {
        this.kitchenQueue = new LinkedBlockingQueue<>();
        this.readyOrders = new LinkedBlockingQueue<>();
        this.kitchen = Executors.newFixedThreadPool(kitchenSize);
        this.waiters = new ArrayList<>();
        this.waiterThreads = new ArrayList<>();

        for (int i = 1; i <= waiterCount; i++) {
            waiters.add(new Waiter("Waiter-" + i, kitchenQueue, readyOrders));
        }

        Thread kitchenDispatcher = new Thread(this::processKitchenOrders);
        kitchenDispatcher.setDaemon(true);
        kitchenDispatcher.start();
    }

    public void start() {
        running = true;
        System.out.println("Ресторан открывается!");

        for (Waiter waiter : waiters) {
            Thread thread = new Thread(waiter);
            waiterThreads.add(thread);
            thread.start();
        }

        Thread monitor = new Thread(this::monitorQueues);
        monitor.setDaemon(true);
        monitor.start();
    }

    public void stop() {
        running = false;
        System.out.println("Ресторан закрывается...");

        for (Waiter waiter : waiters) {
            waiter.stop();
        }

        for (Thread thread : waiterThreads) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        kitchen.shutdown();
        try {
            if (!kitchen.awaitTermination(5, TimeUnit.SECONDS)) {
                kitchen.shutdownNow();
            }
        } catch (InterruptedException e) {
            kitchen.shutdownNow();
        }

        System.out.println("Ресторан закрыт.");
    }

    private void processKitchenOrders() {
        while (running || !kitchenQueue.isEmpty()) {
            try {
                Order order = kitchenQueue.poll(1, TimeUnit.SECONDS);
                if (order != null) {
                    kitchen.submit(new CookingTask(order, readyOrders));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void monitorQueues() {
        while (running) {
            try {
                Thread.sleep(5000);
                System.out.println("\n=== Мониторинг ===");
                System.out.println("Заказов в очереди на кухне: " + kitchenQueue.size());
                System.out.println("Готовых заказов: " + readyOrders.size());
                System.out.println("Активных поваров: " +
                        ((ThreadPoolExecutor) kitchen).getActiveCount());
                System.out.println("==================\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}

public class RestaurantSystem {
    static void main(String[] args) {
        Restaurant restaurant = new Restaurant(3, 4);

        restaurant.start();

        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        restaurant.stop();
    }
}