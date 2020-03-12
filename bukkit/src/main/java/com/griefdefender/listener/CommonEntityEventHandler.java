/*
 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.griefdefender.listener;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

import com.flowpowered.math.vector.Vector3i;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.griefdefender.GDPlayerData;
import com.griefdefender.GDTimings;
import com.griefdefender.GriefDefenderPlugin;
import com.griefdefender.api.ChatType;
import com.griefdefender.api.ChatTypes;
import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.Tristate;
import com.griefdefender.api.claim.TrustTypes;
import com.griefdefender.api.permission.Context;
import com.griefdefender.api.permission.flag.Flags;
import com.griefdefender.api.permission.option.Options;
import com.griefdefender.api.permission.option.type.GameModeType;
import com.griefdefender.api.permission.option.type.GameModeTypes;
import com.griefdefender.api.permission.option.type.WeatherType;
import com.griefdefender.api.permission.option.type.WeatherTypes;
import com.griefdefender.cache.MessageCache;
import com.griefdefender.cache.PermissionHolderCache;
import com.griefdefender.claim.GDClaim;
import com.griefdefender.command.CommandHelper;
import com.griefdefender.configuration.MessageStorage;
import com.griefdefender.event.GDBorderClaimEvent;
import com.griefdefender.internal.registry.ItemTypeRegistryModule;
import com.griefdefender.internal.util.BlockUtil;
import com.griefdefender.internal.util.NMSUtil;
import com.griefdefender.internal.util.VecHelper;
import com.griefdefender.permission.GDPermissionManager;
import com.griefdefender.permission.GDPermissionUser;
import com.griefdefender.permission.GDPermissions;
import com.griefdefender.permission.flag.GDFlags;
import com.griefdefender.permission.option.OptionContexts;
import com.griefdefender.storage.BaseStorage;
import com.griefdefender.util.PermissionUtil;
import com.griefdefender.util.PlayerUtil;
import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.adapter.bukkit.TextAdapter;

public class CommonEntityEventHandler {

    private static CommonEntityEventHandler instance;

    public static CommonEntityEventHandler getInstance() {
        return instance;
    }

    static {
        instance = new CommonEntityEventHandler();
    }

    private final BaseStorage storage;

    public CommonEntityEventHandler() {
        this.storage = GriefDefenderPlugin.getInstance().dataStore;
    }

    public boolean onEntityMove(Event event, Location fromLocation, Location toLocation, Entity targetEntity){
        final Vector3i fromPos = VecHelper.toVector3i(fromLocation);
        final Vector3i toPos = VecHelper.toVector3i(toLocation);
        if (fromPos.equals(toPos)) {
            return true;
        }
        if ((!GDFlags.ENTER_CLAIM && !GDFlags.EXIT_CLAIM)) {
            return true;
        }

        final Player player = targetEntity instanceof Player ? (Player) targetEntity : null;
        final GDPermissionUser user = player != null ? PermissionHolderCache.getInstance().getOrCreateUser(player) : null;
        if (user != null) {
            if (user.getInternalPlayerData().teleportDelay > 0) {
                if (!toPos.equals(VecHelper.toVector3i(user.getInternalPlayerData().teleportSourceLocation))) {
                    user.getInternalPlayerData().teleportDelay = 0;
                    TextAdapter.sendComponent(player, MessageCache.getInstance().TELEPORT_MOVE_CANCEL);
                }
            }
        }
        final World world = targetEntity.getWorld();
        if (!GriefDefenderPlugin.getInstance().claimsEnabledForWorld(world.getUID())) {
            return true;
        }
        final boolean enterBlacklisted = GriefDefenderPlugin.isSourceIdBlacklisted(Flags.ENTER_CLAIM.getName(), targetEntity, world.getUID());
        final boolean exitBlacklisted = GriefDefenderPlugin.isSourceIdBlacklisted(Flags.EXIT_CLAIM.getName(), targetEntity, world.getUID());
        if (enterBlacklisted && exitBlacklisted) {
            return true;
        }

        GDTimings.ENTITY_MOVE_EVENT.startTiming();

        GDClaim fromClaim = null;
        GDClaim toClaim = this.storage.getClaimAt(toLocation);
        if (user != null) {
            fromClaim = this.storage.getClaimAtPlayer(user.getInternalPlayerData(), fromLocation);
        } else {
            fromClaim = this.storage.getClaimAt(fromLocation);
        }

        if (GDFlags.ENTER_CLAIM && !enterBlacklisted && user != null && user.getInternalPlayerData().lastClaim != null) {
            final GDClaim lastClaim = (GDClaim) user.getInternalPlayerData().lastClaim.get();
            if (lastClaim != null && lastClaim != fromClaim) {
                if (GDPermissionManager.getInstance().getFinalPermission(event, toLocation, toClaim, Flags.ENTER_CLAIM, targetEntity, targetEntity, player, TrustTypes.ACCESSOR, true) == Tristate.FALSE) {
                    Location claimCorner = new Location(toLocation.getWorld(), toClaim.lesserBoundaryCorner.getX(), targetEntity.getLocation().getBlockY(), toClaim.greaterBoundaryCorner.getZ());
                    targetEntity.teleport(claimCorner);
                }
            }
        }
        if (fromClaim == toClaim) {
            GDTimings.ENTITY_MOVE_EVENT.stopTiming();
            return true;
        }

        GDBorderClaimEvent gpEvent = new GDBorderClaimEvent(targetEntity, fromClaim, toClaim);
        if (user != null && toClaim.isUserTrusted(user, TrustTypes.ACCESSOR)) {
            GriefDefender.getEventManager().post(gpEvent);
            final GDPlayerData playerData = user.getInternalPlayerData();
            if (gpEvent.cancelled()) {
                if (targetEntity instanceof Vehicle) {
                    final Vehicle vehicle = (Vehicle) targetEntity;
                    vehicle.teleport(fromLocation);
                    GDTimings.ENTITY_MOVE_EVENT.stopTiming();
                    return false;
                }
                if (event instanceof Cancellable) {
                    ((Cancellable) event).setCancelled(true);
                }
                final Component cancelMessage = gpEvent.getMessage().orElse(null);
                if (player != null && cancelMessage != null) {
                    TextAdapter.sendComponent(player, cancelMessage);
                }
                return false;
            } else {
                final boolean showGpPrefix = GriefDefenderPlugin.getGlobalConfig().getConfig().message.enterExitShowGdPrefix;
                user.getInternalPlayerData().lastClaim = new WeakReference<>(toClaim);
                TextComponent welcomeMessage = (TextComponent) gpEvent.getEnterMessage().orElse(null);
                if (welcomeMessage != null && !welcomeMessage.equals(TextComponent.empty())) {
                    ChatType chatType = gpEvent.getEnterMessageChatType();
                    if (chatType == ChatTypes.ACTION_BAR) {
                        TextAdapter.sendActionBar(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(welcomeMessage)
                                .build());
                    } else {
                        TextAdapter.sendComponent(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(welcomeMessage)
                                .build());
                    }
                }

                Component farewellMessage = gpEvent.getExitMessage().orElse(null);
                if (farewellMessage != null && !farewellMessage.equals(TextComponent.empty())) {
                    ChatType chatType = gpEvent.getExitMessageChatType();
                    if (chatType == ChatTypes.ACTION_BAR) {
                        TextAdapter.sendActionBar(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(farewellMessage)
                                .build());
                    } else {
                        TextAdapter.sendComponent(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(farewellMessage)
                                .build());
                    }
                }

                if (toClaim.isInTown()) {
                    playerData.inTown = true;
                } else {
                    playerData.inTown = false;
                }
                if (player != null) {
                    this.checkPlayerFlight(user, fromClaim, toClaim);
                    this.checkPlayerGameMode(user, fromClaim, toClaim);
                    this.checkPlayerGodMode(user, fromClaim, toClaim);
                    this.checkPlayerWalkSpeed(user, fromClaim, toClaim);
                    this.checkPlayerWeather(user, fromClaim, toClaim, false);
                    this.runPlayerCommands(fromClaim, user, false);
                    this.runPlayerCommands(toClaim, user, true);
                }
            }

            GDTimings.ENTITY_MOVE_EVENT.stopTiming();
            return true;
        }

        if (fromClaim != toClaim) {
            boolean enterCancelled = false;
            boolean exitCancelled = false;
            // enter
            if (GDFlags.ENTER_CLAIM && !enterBlacklisted && GDPermissionManager.getInstance().getFinalPermission(event, toLocation, toClaim, Flags.ENTER_CLAIM, targetEntity, targetEntity, user, true) == Tristate.FALSE) {
                enterCancelled = true;
                gpEvent.cancelled(true);
            }

            // exit
            if (GDFlags.EXIT_CLAIM && !exitBlacklisted && GDPermissionManager.getInstance().getFinalPermission(event, fromLocation, fromClaim, Flags.EXIT_CLAIM, targetEntity, targetEntity, user, true) == Tristate.FALSE) {
                exitCancelled = true;
                gpEvent.cancelled(true);
            }

            GriefDefender.getEventManager().post(gpEvent);
            if (gpEvent.cancelled()) {
                final Component cancelMessage = gpEvent.getMessage().orElse(null);
                if (exitCancelled) {
                    if (cancelMessage != null && player != null) {
                        GriefDefenderPlugin.sendClaimDenyMessage(fromClaim, player, MessageCache.getInstance().PERMISSION_CLAIM_EXIT);
                    }
                } else if (enterCancelled) {
                    if (cancelMessage != null && player != null) {
                        GriefDefenderPlugin.sendClaimDenyMessage(toClaim, player, MessageCache.getInstance().PERMISSION_CLAIM_ENTER);
                    }
                }

                if (cancelMessage != null && player != null) {
                    TextAdapter.sendComponent(player, cancelMessage);
                }

                if (targetEntity instanceof Vehicle) {
                    final Vehicle vehicle = (Vehicle) targetEntity;
                    vehicle.teleport(fromLocation);
                    GDTimings.ENTITY_MOVE_EVENT.stopTiming();
                    return false;
                }
                if (event instanceof Cancellable) {
                    ((Cancellable) event).setCancelled(true);
                }
                GDTimings.ENTITY_MOVE_EVENT.stopTiming();
                return false;
            }

            if (user != null) {
                final GDPlayerData playerData = user.getInternalPlayerData();
                final boolean showGpPrefix = GriefDefenderPlugin.getGlobalConfig().getConfig().message.enterExitShowGdPrefix;
                playerData.lastClaim = new WeakReference<>(toClaim);
                Component welcomeMessage = gpEvent.getEnterMessage().orElse(null);
                if (welcomeMessage != null && !welcomeMessage.equals(TextComponent.empty())) {
                    ChatType chatType = gpEvent.getEnterMessageChatType();
                    if (chatType == ChatTypes.ACTION_BAR) {
                        TextAdapter.sendActionBar(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(welcomeMessage)
                                .build());
                    } else {
                        TextAdapter.sendComponent(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(welcomeMessage)
                                .build());
                    }
                }

                Component farewellMessage = gpEvent.getExitMessage().orElse(null);
                if (farewellMessage != null && !farewellMessage.equals(TextComponent.empty())) {
                    ChatType chatType = gpEvent.getExitMessageChatType();
                    if (chatType == ChatTypes.ACTION_BAR) {
                        TextAdapter.sendActionBar(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(farewellMessage)
                                .build());
                    } else {
                        TextAdapter.sendComponent(player, TextComponent.builder("")
                                .append(showGpPrefix ? GriefDefenderPlugin.GD_TEXT : TextComponent.empty())
                                .append(farewellMessage)
                                .build());
                    }
                }

                if (toClaim.isInTown()) {
                    playerData.inTown = true;
                } else {
                    playerData.inTown = false;
                }

                if (player != null) {
                    this.checkPlayerFlight(user, fromClaim, toClaim);
                    this.checkPlayerGameMode(user, fromClaim, toClaim);
                    this.checkPlayerGodMode(user, fromClaim, toClaim);
                    this.checkPlayerWalkSpeed(user, fromClaim, toClaim);
                    this.checkPlayerWeather(user, fromClaim, toClaim, false);
                    this.runPlayerCommands(fromClaim, user, false);
                    this.runPlayerCommands(toClaim, user, true);
                }
            }
        }

        GDTimings.ENTITY_MOVE_EVENT.stopTiming();
        return true;
    }

    final static Pattern pattern = Pattern.compile("([^\\s]+)", Pattern.MULTILINE);

    private void runPlayerCommands(GDClaim claim, GDPermissionUser user, boolean enter) {
        final Player player = user.getOnlinePlayer();
        if (player == null) {
            // Most likely Citizens NPC
            return;
        }

        List<String> rawCommandList = new ArrayList<>();
        Set<Context> contexts = new HashSet<>();
        if (player.getUniqueId().equals(claim.getOwnerUniqueId())) {
            contexts.add(OptionContexts.COMMAND_RUNFOR_OWNER);
        } else {
            contexts.add(OptionContexts.COMMAND_RUNFOR_MEMBER);
        }
        contexts.add(OptionContexts.COMMAND_RUNFOR_PUBLIC);
        // Check console commands
        contexts.add(OptionContexts.COMMAND_RUNAS_CONSOLE);
        if (enter) {
            rawCommandList = GDPermissionManager.getInstance().getInternalOptionValue(new TypeToken<List<String>>() {}, user, Options.PLAYER_COMMAND_ENTER, claim, contexts);
        } else {
            rawCommandList = GDPermissionManager.getInstance().getInternalOptionValue(new TypeToken<List<String>>() {}, user, Options.PLAYER_COMMAND_EXIT, claim, contexts);
        }

        if (rawCommandList != null) {
            runCommand(claim, player, rawCommandList, true);
        }

        // Check player commands
        contexts.remove(OptionContexts.COMMAND_RUNAS_CONSOLE);
        contexts.add(OptionContexts.COMMAND_RUNAS_PLAYER);
        if (enter) {
            rawCommandList = GDPermissionManager.getInstance().getInternalOptionValue(new TypeToken<List<String>>() {}, user, Options.PLAYER_COMMAND_ENTER, claim, contexts);
        } else {
            rawCommandList = GDPermissionManager.getInstance().getInternalOptionValue(new TypeToken<List<String>>() {}, user, Options.PLAYER_COMMAND_EXIT, claim, contexts);
        }

        if (rawCommandList != null) {
            runCommand(claim, player, rawCommandList, false);
        }
    }

    private void runCommand(GDClaim claim, Player player, List<String> rawCommandList, boolean runAsConsole) {
        final List<String> commands = new ArrayList<>();
        for (String command : rawCommandList) {
            commands.add(this.replacePlaceHolders(claim, player, command));
        }
        for (String command : commands) {
            final Matcher matcher = pattern.matcher(command);
            if (matcher.find()) {
                String baseCommand = matcher.group(0);
                String args = command.replace(baseCommand + " ", "");
                baseCommand = baseCommand.replace("\\", "").replace("/", "");
                args = args.replace("%player%", player.getName());
                if (runAsConsole) {
                    CommandHelper.executeCommand(Bukkit.getConsoleSender(), baseCommand, args);
                } else {
                    CommandHelper.executeCommand(player, baseCommand, args);
                }
            }
        }
    }

    private String replacePlaceHolders(GDClaim claim, Player player, String command) {
        command = command
                .replace("%player%", player.getName())
                .replace("%owner%", claim.getOwnerFriendlyName())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%world%", claim.getWorld().getName())
                .replace("%server%", PermissionUtil.getInstance().getServerName())
                .replace("%location%", BlockUtil.getInstance().posToString(player.getLocation()));
        return command;
    }

    private void checkPlayerFlight(GDPermissionUser user, GDClaim fromClaim, GDClaim toClaim) {
        final Player player = user.getOnlinePlayer();
        if (player == null) {
            // Most likely Citizens NPC
            return;
        }

        final GDPlayerData playerData = user.getInternalPlayerData();
        final GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) {
            return;
        }

        if (fromClaim == toClaim || !player.isFlying()) {
            return;
        }

        final Boolean noFly = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(Boolean.class), playerData.getSubject(), Options.PLAYER_DENY_FLIGHT, toClaim);
        final boolean adminFly = player.hasPermission(GDPermissions.BYPASS_OPTION + "." + Options.PLAYER_DENY_FLIGHT.getName().toLowerCase());
        final boolean ownerFly = toClaim.isBasicClaim() ? player.hasPermission(GDPermissions.USER_OPTION_PERK_OWNER_FLY_BASIC) : toClaim.isTown() ? player.hasPermission(GDPermissions.USER_OPTION_PERK_OWNER_FLY_TOWN) : false;
        if (player.getUniqueId().equals(toClaim.getOwnerUniqueId()) && ownerFly) {
            return;
        }
        if (!adminFly && noFly) {
            player.setAllowFlight(false);
            player.setFlying(false);
            playerData.ignoreFallDamage = true;
            GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().OPTION_APPLY_PLAYER_DENY_FLIGHT);
        }
    }

    private void checkPlayerGodMode(GDPermissionUser user, GDClaim fromClaim, GDClaim toClaim) {
        final Player player = user.getOnlinePlayer();
        if (player == null) {
            // Most likely Citizens NPC
            return;
        }

        final GDPlayerData playerData = user.getInternalPlayerData();
        final GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR || !player.isInvulnerable()) {
            return;
        }

        if (fromClaim == toClaim) {
            return;
        }

        final Boolean noGodMode = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(Boolean.class), playerData.getSubject(), Options.PLAYER_DENY_GODMODE, toClaim);
        final boolean bypassOption = player.hasPermission(GDPermissions.BYPASS_OPTION + "." + Options.PLAYER_DENY_GODMODE.getName().toLowerCase());
        if (!bypassOption && noGodMode) {
            player.setInvulnerable(false);
            GriefDefenderPlugin.sendMessage(player, MessageCache.getInstance().OPTION_APPLY_PLAYER_DENY_GODMODE);
        }
    }

    private void checkPlayerGameMode(GDPermissionUser user, GDClaim fromClaim, GDClaim toClaim) {
        if (fromClaim == toClaim) {
            return;
        }

        final Player player = user.getOnlinePlayer();
        if (player == null) {
            // Most likely Citizens NPC
            return;
        }

        final GDPlayerData playerData = user.getInternalPlayerData();
        final GameMode currentGameMode = player.getGameMode();
        final GameModeType gameModeType = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(GameModeType.class), playerData.getSubject(), Options.PLAYER_GAMEMODE, toClaim);
        final boolean bypassOption = player.hasPermission(GDPermissions.BYPASS_OPTION + "." + Options.PLAYER_GAMEMODE.getName().toLowerCase());
        if (!bypassOption && gameModeType != null && gameModeType != GameModeTypes.UNDEFINED) {
            final GameMode newGameMode = PlayerUtil.GAMEMODE_MAP.get(gameModeType);
            if (currentGameMode != newGameMode) {
                player.setGameMode(newGameMode);
                final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.OPTION_APPLY_PLAYER_GAMEMODE,
                        ImmutableMap.of(
                        "gamemode", gameModeType.getName()));
                GriefDefenderPlugin.sendMessage(player, message);
            }
        }
    }

    private void checkPlayerWalkSpeed(GDPermissionUser user, GDClaim fromClaim, GDClaim toClaim) {
        if (fromClaim == toClaim) {
            return;
        }

        final Player player = user.getOnlinePlayer();
        if (player == null) {
            // Most likely Citizens NPC
            return;
        }

        final GDPlayerData playerData = user.getInternalPlayerData();
        final float currentWalkSpeed = player.getWalkSpeed();
        final double walkSpeed = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(Double.class), playerData.getSubject(), Options.PLAYER_WALK_SPEED, toClaim);
        final boolean bypassOption = player.hasPermission(GDPermissions.BYPASS_OPTION + "." + Options.PLAYER_WALK_SPEED.getName().toLowerCase());
        if (!bypassOption && walkSpeed > 0) {
            if (currentWalkSpeed != ((float) walkSpeed)) {
                player.setWalkSpeed((float) walkSpeed);
                final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.OPTION_APPLY_PLAYER_WALK_SPEED,
                        ImmutableMap.of(
                        "speed", walkSpeed));
                GriefDefenderPlugin.sendMessage(player, message);
            }
        }
    }

    public void checkPlayerWeather(GDPermissionUser user, GDClaim fromClaim, GDClaim toClaim, boolean force) {
        if (!force && fromClaim == toClaim) {
            return;
        }

        final Player player = user.getOnlinePlayer();
        if (player == null) {
            // Most likely Citizens NPC
            return;
        }

        final GDPlayerData playerData = user.getInternalPlayerData();
        final WeatherType weatherType = GDPermissionManager.getInstance().getInternalOptionValue(TypeToken.of(WeatherType.class), playerData.getSubject(), Options.PLAYER_WEATHER, toClaim);
        if (weatherType != null && weatherType != WeatherTypes.UNDEFINED) {
            final org.bukkit.WeatherType currentWeather = player.getPlayerWeather();
            player.setPlayerWeather(PlayerUtil.WEATHERTYPE_MAP.get(weatherType));
            final org.bukkit.WeatherType newWeather = player.getPlayerWeather();
            // TODO - improve so it doesn't spam
            /*if (currentWeather != newWeather) {
                final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.OPTION_APPLY_PLAYER_WEATHER,
                        ImmutableMap.of(
                        "weather", weatherType.getName()));
                GriefDefenderPlugin.sendMessage(player, message);
            }*/
        }
    }

    public void sendInteractEntityDenyMessage(ItemStack playerItem, Entity entity, GDClaim claim, Player player) {
        if (entity instanceof Player || (claim.getData() != null && !claim.getData().allowDenyMessages())) {
            return;
        }

        final String entityId = entity.getType().getName() == null ? entity.getType().name().toLowerCase() : entity.getType().getName();
        if (playerItem == null || playerItem.getType() == Material.AIR) {
            final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.PERMISSION_INTERACT_ENTITY, ImmutableMap.of(
                    "player", claim.getOwnerName(),
                    "entity", entityId));
            GriefDefenderPlugin.sendClaimDenyMessage(claim, player, message);
        } else {
            final Component message = GriefDefenderPlugin.getInstance().messageData.getMessage(MessageStorage.PERMISSION_INTERACT_ITEM_ENTITY, ImmutableMap.of(
                    "item", ItemTypeRegistryModule.getInstance().getNMSKey(playerItem),
                    "entity", entityId));
            GriefDefenderPlugin.sendClaimDenyMessage(claim, player, message);
        }
    }
}
