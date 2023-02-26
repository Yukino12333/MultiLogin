package moe.caa.multilogin.core.command.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import lombok.SneakyThrows;
import moe.caa.multilogin.api.auth.GameProfile;
import moe.caa.multilogin.api.plugin.IPlayer;
import moe.caa.multilogin.api.plugin.ISender;
import moe.caa.multilogin.api.util.Pair;
import moe.caa.multilogin.api.util.ValueUtil;
import moe.caa.multilogin.core.command.CommandHandler;
import moe.caa.multilogin.core.command.Permissions;
import moe.caa.multilogin.core.command.argument.OnlineArgumentType;
import moe.caa.multilogin.core.command.argument.ProfileArgumentType;
import moe.caa.multilogin.core.command.argument.StringArgumentType;
import moe.caa.multilogin.core.command.argument.UUIDArgumentType;
import moe.caa.multilogin.core.main.MultiCore;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public class MProfileCommand {

    private final CommandHandler handler;

    public MProfileCommand(CommandHandler handler) {
        this.handler = handler;
    }

    public LiteralArgumentBuilder<ISender> register(LiteralArgumentBuilder<ISender> literalArgumentBuilder) {
        return literalArgumentBuilder
                .then(handler.literal("create")
                        .requires(iSender -> iSender.hasPermission(Permissions.COMMAND_MULTI_LOGIN_PROFILE_CREATE))
                        .then(handler.argument("username", StringArgumentType.string())
                                .then(handler.argument("ingameuuid", UUIDArgumentType.uuid())
                                        .executes(this::executeCreate))
                                .executes(this::executeCreateRandomUUID)))
                .then(handler.literal("set")
                        .then(handler.argument("profile", ProfileArgumentType.profile())
                                .requires(iSender -> iSender.hasPermission(Permissions.COMMAND_MULTI_LOGIN_PROFILE_SET_ONESELF))
                                .executes(this::executeSetOneself))
                        .then(handler.argument("profile", ProfileArgumentType.profile())
                                .then(handler.argument("online", OnlineArgumentType.online())
                                        .requires(iSender -> iSender.hasPermission(Permissions.COMMAND_MULTI_LOGIN_PROFILE_SET_OTHER))
                                        .executes(this::executeSetOther)))
                )
                .then(handler.literal("settemp")
                        .then(handler.argument("profile", ProfileArgumentType.profile())
                                .requires(iSender -> iSender.hasPermission(Permissions.COMMAND_MULTI_LOGIN_PROFILE_SET_ONESELF))
                                .executes(this::executeSetTempOneself))
                        .then(handler.argument("profile", ProfileArgumentType.profile())
                                .then(handler.argument("online", OnlineArgumentType.online())
                                        .requires(iSender -> iSender.hasPermission(Permissions.COMMAND_MULTI_LOGIN_PROFILE_SET_OTHER))
                                        .executes(this::executeSetTempOther)))
                )
                .then(handler.literal("remove")
                        .then(handler.argument("profile", ProfileArgumentType.profile())
                                .requires(iSender -> iSender.hasPermission(Permissions.COMMAND_MULTI_LOGIN_PROFILE_REMOVE))
                                .executes(this::executeRemove)));
    }

    @SneakyThrows
    private int executeRemove(CommandContext<ISender> context) {
        ProfileArgumentType.ProfileArgument profile = ProfileArgumentType.getProfile(context, "profile");

        String name = Optional.ofNullable(
                profile.getProfileName()
        ).orElse(CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_remove_unnamed"));

        handler.getSecondaryConfirmationHandler().submit(context.getSource(), () -> {
            CommandHandler.getCore().getSqlManager().getInGameProfileTable().remove(profile.getProfileUUID());
            Map<Pair<Integer, UUID>, UUID> map = CommandHandler.getCore().getTemplateProfileRedirectHandler().getTemplateProfileRedirectMap();
            map.entrySet().removeIf(e -> e.getValue().equals(profile.getProfileUUID()));

            context.getSource().sendMessagePL(
                    CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_remove_succeed",
                            new Pair<>("name", name),
                            new Pair<>("uuid", profile.getProfileUUID())
                    ));

            IPlayer player = CommandHandler.getCore().getPlugin().getRunServer().getPlayerManager().getPlayer(profile.getProfileUUID());
            if (player != null) {
                player.kickPlayer(CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_remove_kickmessage"));
            }

        }, CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_remove_desc",
                new Pair<>("name", name),
                new Pair<>("uuid", profile.getProfileUUID())
        ), CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_remove_cq"));
        return 0;
    }

    @SneakyThrows
    private int executeSetTempOther(CommandContext<ISender> context) {
        ProfileArgumentType.ProfileArgument profile = ProfileArgumentType.getProfile(context, "profile");
        OnlineArgumentType.OnlineArgument online = OnlineArgumentType.getOnline(context, "online");
        processSetTemp(context, online.getOnlineUUID(), online.getBaseServiceConfig().getId(), profile);
        return 0;
    }

    @SneakyThrows
    private int executeSetOther(CommandContext<ISender> context) {
        ProfileArgumentType.ProfileArgument profile = ProfileArgumentType.getProfile(context, "profile");
        OnlineArgumentType.OnlineArgument online = OnlineArgumentType.getOnline(context, "online");
        processSet(context, online.getOnlineUUID(), online.getBaseServiceConfig().getId(), profile);
        return 0;
    }

    @SneakyThrows
    private int executeSetTempOneself(CommandContext<ISender> context) {
        ProfileArgumentType.ProfileArgument profile = ProfileArgumentType.getProfile(context, "profile");
        Pair<GameProfile, Integer> pair = handler.requireDataCacheArgument(context);

        processSetTemp(context, pair.getValue1().getId(), pair.getValue2(), profile);
        return 0;
    }

    @SneakyThrows
    private int executeSetOneself(CommandContext<ISender> context) {
        ProfileArgumentType.ProfileArgument profile = ProfileArgumentType.getProfile(context, "profile");
        Pair<GameProfile, Integer> pair = handler.requireDataCacheArgument(context);

        processSet(context, pair.getValue1().getId(), pair.getValue2(), profile);
        return 0;
    }

    private void processSet(CommandContext<ISender> context, UUID from, int serviceId, ProfileArgumentType.ProfileArgument to) {
        handler.getSecondaryConfirmationHandler().submit(context.getSource(), () -> {

            CommandHandler.getCore().getSqlManager().getUserDataTable().setInGameUUID(from, serviceId, to.getProfileUUID());
            context.getSource().sendMessagePL(
                    CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_succeed",
                            new Pair<>("current_username", to.getProfileName())
                    )
            );

            context.getSource().getAsPlayer().kickPlayer(
                    CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_succeed_kickmessage",
                            new Pair<>("current_username", to.getProfileName())
                    )
            );
        }, CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_desc",
                new Pair<>("current_username", to.getProfileName())
        ), CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_cq"));
    }

    private void processSetTemp(CommandContext<ISender> context, UUID from, int serviceId, ProfileArgumentType.ProfileArgument to) {
        handler.getSecondaryConfirmationHandler().submit(context.getSource(), () -> {

            CommandHandler.getCore().getTemplateProfileRedirectHandler().getTemplateProfileRedirectMap().put(
                    new Pair<>(serviceId, from),
                    to.getProfileUUID()
            );
            context.getSource().sendMessagePL(
                    CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_temp_succeed",
                            new Pair<>("current_username", to.getProfileName())
                    )
            );

            context.getSource().getAsPlayer().kickPlayer(
                    CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_temp_succeed_kickmessage",
                            new Pair<>("current_username", to.getProfileName())
                    )
            );
        }, CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_temp_desc",
                new Pair<>("current_username", to.getProfileName())
        ), CommandHandler.getCore().getLanguageHandler().getMessage("command_message_profile_set_temp_cq"));
    }

    private void processCreate(CommandContext<ISender> context, String name, UUID uuid) throws SQLException {
        MultiCore core = CommandHandler.getCore();
        String nameAllowedRegular = core.getPluginConfig().getNameAllowedRegular();
        if (!ValueUtil.isEmpty(nameAllowedRegular)) {
            if (!Pattern.matches(nameAllowedRegular, name)) {
                context.getSource().sendMessagePL(
                        core.getLanguageHandler().getMessage("command_message_profile_create_namemismatch",
                                new Pair<>("current_username", name),
                                new Pair<>("name_allowed_regular", nameAllowedRegular)
                        )
                );
                return;
            }
        }
        if (uuid.version() < 2) {
            context.getSource().sendMessagePL(
                    core.getLanguageHandler().getMessage("command_message_profile_create_uuidmismatch",
                            new Pair<>("uuid", uuid)
                    )
            );
            return;
        }
        if (core.getSqlManager().getInGameProfileTable().dataExists(uuid)) {
            context.getSource().sendMessagePL(
                    core.getLanguageHandler().getMessage("command_message_profile_create_uuidoccupied",
                            new Pair<>("uuid", uuid)
                    )
            );
            return;
        }
        if (core.getSqlManager().getInGameProfileTable().getInGameUUIDIgnoreCase(name) != null) {
            context.getSource().sendMessagePL(
                    core.getLanguageHandler().getMessage("command_message_profile_create_nameoccupied",
                            new Pair<>("username", name)
                    )
            );
            return;
        }
        core.getSqlManager().getInGameProfileTable().insertNewData(uuid, name);
        context.getSource().sendMessagePL(
                core.getLanguageHandler().getMessage("command_message_profile_create",
                        new Pair<>("username", name),
                        new Pair<>("ingameuuid", uuid)
                )
        );
    }

    @SneakyThrows
    private int executeCreate(CommandContext<ISender> context) {
        String username = StringArgumentType.getString(context, "username");
        UUID ingameuuid = UUIDArgumentType.getUuid(context, "ingameuuid");
        processCreate(context, username, ingameuuid);
        return 0;
    }

    @SneakyThrows
    private int executeCreateRandomUUID(CommandContext<ISender> context) {
        String username = StringArgumentType.getString(context, "username");
        UUID ingameuuid = UUID.randomUUID();
        processCreate(context, username, ingameuuid);
        return 0;
    }
}
