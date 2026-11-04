package com.lmj.voicecall;

import com.lmj.voicecall.server.ServerChannelManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * /call join &lt;频道&gt; / leave / status / list / help
 */
public final class ServerCommands {

    private ServerCommands() {
    }

    private static void usage(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("§6===== 通话指令 ====="));
        player.sendSystemMessage(Component.literal("§e/call join <编号> §7- 加入频道（0-999）"));
        player.sendSystemMessage(Component.literal("§e/call leave §7- 退出频道"));
        player.sendSystemMessage(Component.literal("§e/call status §7- 查看自己在哪个频道"));
        player.sendSystemMessage(Component.literal("§e/call list §7- 查看频道成员"));
        player.sendSystemMessage(Component.literal("§7按住 §3~ §7键说话"));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("call")
                        .executes(ctx -> {
                            usage(ctx.getSource().getPlayerOrException());
                            return 1;
                        })
                        .then(Commands.literal("join")
                                .then(Commands.argument("channel", IntegerArgumentType.integer(0, 999))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            int channel = IntegerArgumentType.getInteger(ctx, "channel");
                                            ServerChannelManager.join(player, channel);
                                            return 1;
                                        })))
                        .then(Commands.literal("leave")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (ServerChannelManager.getChannel(player) < 0) {
                                        player.sendSystemMessage(Component.literal("§7[通话] 你还没有加入频道"));
                                    } else {
                                        ServerChannelManager.leave(player);
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    int ch = ServerChannelManager.getChannel(player);
                                    if (ch < 0) {
                                        player.sendSystemMessage(Component.literal("§7[通话] 未加入任何频道，用 §e/call join 1§7 加入"));
                                    } else {
                                        int n = ServerChannelManager.countMembers(player.server, ch);
                                        player.sendSystemMessage(Component.literal(
                                                "§a[通话] 当前频道 §e" + ch + "§a，共 §e" + n + "§a 人"));
                                        List<String> names = ServerChannelManager.memberNames(player.server, ch);
                                        if (!names.isEmpty()) {
                                            player.sendSystemMessage(Component.literal("  §7成员: §f" + String.join("§7, §f", names)));
                                        }
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    int ch = ServerChannelManager.getChannel(player);
                                    if (ch < 0) {
                                        player.sendSystemMessage(Component.literal("§7[通话] 未加入任何频道"));
                                    } else {
                                        List<String> names = ServerChannelManager.memberNames(player.server, ch);
                                        player.sendSystemMessage(Component.literal(
                                                "§a[通话] 频道 §e" + ch + " §a成员（" + names.size() + "人）: §f"
                                                        + String.join("§7, §f", names)));
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("help")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    usage(player);
                                    return 1;
                                }))
        );
    }
}