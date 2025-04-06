package org.example;

import io.github.natanimn.BotClient;
import io.github.natanimn.types.BotCommand;
import io.github.natanimn.types.Message;

import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Main {
    public static void main(String[] args) {
        initDb();

        final AtomicReference<Message> msgToPublish = new AtomicReference<>();

        final BotClient bot = new BotClient(args[0]);

        bot.context.setMyCommands(new BotCommand[] {
                new BotCommand("set_message", "Sets the message to be published"),
                new BotCommand("send_message_now", "Sends the message now"),
                new BotCommand("add_channel", "Adds a channel to the list"),
                new BotCommand("list_channels", "List all submitted channels"),
                new BotCommand("remove_all_channels", "Removes all submitted channels"),
                new BotCommand("remove_channel", "Removes specified channel"),
        }).exec();



        bot.onMessage(filter -> filter.commands("set_message"), (context, message) -> {
            context.sendMessage("Define message to publish on schedule:").exec();
            context.setState(message.chat.id, "set_message");
        });

        bot.onMessage(filter -> filter.state("set_message") && filter.text(), (context, message) -> {
            msgToPublish.set(message);
            context.clearState(message.chat.id);
            context.sendMessage("Message defined").exec();
        });



        bot.onMessage(filter -> filter.commands("send_message_now"), (context, message) -> {
            context.sendMessage("Define message to publish:").exec();
            context.setState(message.chat.id, "send_message_now");
        });

        bot.onMessage(filter -> filter.state("send_message_now") && filter.text(), (context, message) -> {
            getChannels().parallelStream().forEach(channel -> context.copyMessage(channel, message.message_id).exec());
            context.clearState(message.chat.id);
            context.sendMessage("Message published").exec();
        });



        bot.onMessage(filter -> filter.commands("add_channel"), (context, message) -> {
            context.sendMessage("Write channel id (example: @channelId)").exec();
            context.setState(message.chat.id, "add_channel");
        });

        bot.onMessage(filter -> filter.state("add_channel") && filter.text(), (context, message) -> {
            if (getChannels().contains(message.text)) {
                context.sendMessage("Channel already submitted").exec();
                return;
            }

            addChannel(message.text);
            context.clearState(message.chat.id);
            context.sendMessage("Channel added").exec();
        });



        bot.onMessage(filter -> filter.commands("list_channels"), (context, _) -> {
            if (getChannels().isEmpty()) {
                context.sendMessage("No channels found").exec();
                return;
            }

            final String answer = getChannels().stream().reduce((a, b) -> a + ", " + b).orElse("");
            context.sendMessage(answer).exec();
        });



        bot.onMessage(filter -> filter.commands("remove_all_channels"), (context, _) -> {
            dropDb();
            context.sendMessage("All channels removed").exec();
        });



        bot.onMessage(filter -> filter.commands("remove_channel"), (context, message) -> {
            context.sendMessage("Write channel id (example: @channelId)").exec();
            context.setState(message.chat.id, "remove_channel");
        });

        bot.onMessage(filter -> filter.state("remove_channel") && filter.text(), (context, message) -> {
            removeChanned(message.text);
            context.clearState(message.chat.id);
            context.sendMessage("Channel deleted").exec();
        });

        final ScheduledExecutorService executor = Executors.newScheduledThreadPool(20);
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    getChannels().parallelStream().forEach(channel -> bot.context.copyMessage(channel, msgToPublish.get().message_id));
                } finally {
                    // Schedule the next execution after this one completes
                    executor.schedule(this, computeNextDelay(), TimeUnit.MILLISECONDS);
                }
            }
        };

        // Initial scheduling
        executor.schedule(task, computeNextDelay(), TimeUnit.MILLISECONDS);

        // Graceful shutdown handling
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }));

        bot.run();
    }

    private static long computeNextDelay() {
        final LocalDateTime now = LocalDateTime.now();
        final LocalDateTime nextRun;

        // Check if current time is before 8 AM today
        final LocalDateTime today8AM = now.withHour(8).withMinute(0).withSecond(0).withNano(0);
        if (now.isBefore(today8AM)) {
            nextRun = today8AM;
        }
        // Check if current time is after 10 PM today
        else if (now.getHour() >= 22) {
            nextRun = today8AM.plusDays(1);
        }
        // Calculate next hour within 8 AM - 10 PM window
        else {
            int nextHour = now.getHour() + 2;
            // If next hour exceeds 10 PM, schedule for next day 8 AM
            nextRun = (nextHour > 22)
                    ? today8AM.plusDays(1)
                    : now.withHour(nextHour).withMinute(0).withSecond(0).withNano(0);
        }

        return Duration.between(now, nextRun).toMillis();
    }

    private static void addChannel(String channel) {
        try (final Connection con = DriverManager.getConnection("jdbc:sqlite:db.db");
             final Statement stmt = con.createStatement()) {

            stmt.setQueryTimeout(30);

            stmt.executeUpdate("insert into tb_channel values(NULL, '" + channel + "')");
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    private static List<String> getChannels() {
        try (final Connection con = DriverManager.getConnection("jdbc:sqlite:db.db");
             final Statement stmt = con.createStatement()) {

            final List<String> channels = new LinkedList<>();

            stmt.setQueryTimeout(30);

            final ResultSet res = stmt.executeQuery("select name from tb_channel");
            while (res.next()) {
                channels.add(res.getString(1));
            }
            return channels;
        } catch (SQLException e) {
            System.err.println(e.getMessage());
            return List.of();
        }
    }

    private static void removeChanned(String name) {
        try (final Connection con = DriverManager.getConnection("jdbc:sqlite:db.db");
             final Statement stmt = con.createStatement()) {

            stmt.setQueryTimeout(30);

            stmt.executeUpdate("delete from tb_channel where name = '" + name + "'");
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }


    private static void initDb() {
        try (final Connection con = DriverManager.getConnection("jdbc:sqlite:db.db");
             final Statement stmt = con.createStatement()) {

            stmt.setQueryTimeout(30);

            stmt.executeUpdate("create table if not exists tb_channel (id integer primary key, name text)");
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    private static void dropDb() {
        try (final Connection con = DriverManager.getConnection("jdbc:sqlite:db.db");
             final Statement stmt = con.createStatement()) {

            stmt.setQueryTimeout(30);

            stmt.executeUpdate("delete from tb_channel");
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }
}