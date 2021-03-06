/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.extension.platform;

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.command.tool.*;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.event.platform.BlockInteractEvent;
import com.sk89q.worldedit.event.platform.Interaction;
import com.sk89q.worldedit.event.platform.PlayerInputEvent;
import com.sk89q.worldedit.internal.ServerInterfaceAdapter;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.Subscribe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manages registered {@link Platform}s for WorldEdit. Platforms are
 * implementations of WorldEdit.
 * </p>
 * This class is thread-safe.
 */
public class PlatformManager {

    private static final Logger logger = Logger.getLogger(PlatformManager.class.getCanonicalName());

    private final LocalConfiguration defaultConfig = new DefaultConfiguration();
    private final List<Platform> platforms = new ArrayList<Platform>();
    private final WorldEdit worldEdit;
    private final CommandManager commandManager;
    private @Nullable Platform primary = null;

    /**
     * Create a new platform manager.
     *
     * @param worldEdit the WorldEdit instance
     */
    public PlatformManager(WorldEdit worldEdit) {
        checkNotNull(worldEdit);
        this.worldEdit = worldEdit;
        this.commandManager = new CommandManager(worldEdit);

        // Register this instance for events
        worldEdit.getEventBus().register(this);
    }

    /**
     * Register a platform with WorldEdit.
     *
     * @param platform the platform
     * @throws PlatformRejectionException thrown if the registration is rejected
     */
    public synchronized void register(Platform platform) throws PlatformRejectionException {
        checkNotNull(platform);
        logger.log(Level.FINE, "Got request to register " + platform.getClass() + " with WorldEdit [" + super.toString() + "]");
        platforms.add(platform);

        // Register primary platform
        if (this.primary == null) {
            commandManager.register(platform);
            this.primary = platform;
        } else {
            // Make sure that versions are in sync
            if (!primary.getVersion().equals(platform.getVersion())) {
                logger.log(Level.WARNING,
                        "\n**********************************************\n" +
                        "** There is a mismatch in available WorldEdit platforms!\n" +
                        "**\n" +
                        "** {0} v{1} is trying to register WE version v{2}\n" +
                        "** but the primary platform, {3} v{4}, uses WE version v{5}\n" +
                        "**\n" +
                        "** Things may break! Please make sure that your WE versions are in sync.\n" +
                        "**********************************************\n",
                        new Object[]{
                                platform.getClass(), platform.getPlatformVersion(), platform.getVersion(),
                                primary.getClass(), primary.getPlatformVersion(), primary.getVersion()
                        });
            }
        }
    }

    /**
     * Unregister a platform from WorldEdit.
     *
     * @param platform the platform
     */
    public synchronized boolean unregister(Platform platform) {
        checkNotNull(platform);
        boolean removed = platforms.remove(platform);
        if (removed) {
            logger.log(Level.FINE, "Unregistering " + platform.getClass().getCanonicalName() + " from WorldEdit");

            if (platform == primary) {
                primary = null;
                commandManager.unregister();
            }
        }
        return removed;
    }

    /**
     * Get a list of loaded platforms.
     * </p>
     * The returned list is a copy of the original and is mutable.
     *
     * @return a list of platforms
     */
    public synchronized List<Platform> getPlatforms() {
        return new ArrayList<Platform>(platforms);
    }

    /**
     * Get the primary platform.
     *
     * @return the primary platform (may be null)
     */
    public @Nullable Platform getPrimaryPlatform() {
        return primary;
    }

    /**
     * Get the command manager.
     *
     * @return the command manager
     */
    public CommandManager getCommandManager() {
        return commandManager;
    }

    /**
     * Get the current configuration.
     * </p>
     * If no platform has been registered yet, then a default configuration
     * will be returned.
     *
     * @return the configuration
     */
    public LocalConfiguration getConfiguration() {
        Platform platform = primary;
        if (platform != null) {
            return platform.getConfiguration();
        } else {
            return defaultConfig;
        }
    }
    /**
     * Return a {@link Platform}.
     *
     * @return a {@link Platform}
     * @throws IllegalStateException if no platform has been registered
     */
    public Platform getPlatform() throws IllegalStateException {
        Platform platform = primary;
        if (platform != null) {
            return platform;
        } else {
            throw new IllegalStateException("No platform has been registered");
        }
    }

    /**
     * Return a legacy {@link ServerInterface}.
     *
     * @return a {@link ServerInterface}
     * @throws IllegalStateException if no platform has been registered
     */
    public ServerInterface getServerInterface() throws IllegalStateException {
        Platform platform = primary;
        if (platform != null) {
            if (platform instanceof ServerInterface) {
                return (ServerInterface) platform;
            } else {
                return ServerInterfaceAdapter.adapt(platform);
            }
        } else {
            throw new IllegalStateException("No platform has been registered");
        }
    }

    @Subscribe
    public void handleBlockInteract(BlockInteractEvent event) {
        Actor actor = event.getCause();
        Location location = event.getLocation();
        Vector vector = location.toVector();

        // At this time, only handle interaction from players
        if (actor instanceof Player) {
            Player player = (Player) actor;
            LocalSession session = worldEdit.getSessionManager().get(actor);

            if (event.getType() == Interaction.HIT) {
                if (player.getItemInHand() == getConfiguration().wandItem) {
                    if (!session.isToolControlEnabled()) {
                        return;
                    }

                    if (!actor.hasPermission("worldedit.selection.pos")) {
                        return;
                    }

                    RegionSelector selector = session.getRegionSelector(player.getWorld());

                    if (selector.selectPrimary(location.toVector())) {
                        selector.explainPrimarySelection(actor, session, vector);
                    }

                    event.setCancelled(true);
                    return;
                }

                if (player instanceof LocalPlayer) { // Temporary workaround
                    LocalPlayer localPlayer = (LocalPlayer) player;
                    WorldVector worldVector = new WorldVector(location);

                    if (player.isHoldingPickAxe() && session.hasSuperPickAxe()) {
                        final BlockTool superPickaxe = session.getSuperPickaxe();
                        if (superPickaxe != null && superPickaxe.canUse(localPlayer)) {
                            event.setCancelled(superPickaxe.actPrimary(getServerInterface(), getConfiguration(), localPlayer, session, worldVector));
                            return;
                        }
                    }

                    Tool tool = session.getTool(player.getItemInHand());
                    if (tool != null && tool instanceof DoubleActionBlockTool) {
                        if (tool.canUse(localPlayer)) {
                            ((DoubleActionBlockTool) tool).actSecondary(getServerInterface(), getConfiguration(), localPlayer, session, worldVector);
                            event.setCancelled(true);
                        }
                    }
                }

            } else if (event.getType() == Interaction.OPEN) {
                if (player.getItemInHand() == getConfiguration().wandItem) {
                    if (!session.isToolControlEnabled()) {
                        return;
                    }

                    if (!actor.hasPermission("worldedit.selection.pos")) {
                        return;
                    }

                    RegionSelector selector = session.getRegionSelector(player.getWorld());
                    if (selector.selectSecondary(vector)) {
                        selector.explainSecondarySelection(actor, session, vector);
                    }

                    event.setCancelled(true);
                    return;
                }

                if (player instanceof LocalPlayer) { // Temporary workaround
                    LocalPlayer localPlayer = (LocalPlayer) player;
                    WorldVector worldVector = new WorldVector(location);

                    Tool tool = session.getTool(player.getItemInHand());
                    if (tool != null && tool instanceof BlockTool) {
                        if (tool.canUse(localPlayer)) {
                            ((BlockTool) tool).actPrimary(getServerInterface(), getConfiguration(), localPlayer, session, worldVector);
                            event.setCancelled(true);
                        }
                    }
                }
            }
        }
    }

    @Subscribe
    public void handlePlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();

        switch (event.getInputType()) {
            case PRIMARY: {
                if (player.getItemInHand() == getConfiguration().navigationWand) {
                    if (getConfiguration().navigationWandMaxDistance <= 0) {
                        return;
                    }

                    if (!player.hasPermission("worldedit.navigation.jumpto.tool")) {
                        return;
                    }

                    WorldVector pos = player.getSolidBlockTrace(getConfiguration().navigationWandMaxDistance);
                    if (pos != null) {
                        player.findFreePosition(pos);
                    } else {
                        player.printError("No block in sight (or too far)!");
                    }

                    event.setCancelled(true);
                    return;
                }

                LocalSession session = worldEdit.getSessionManager().get(player);

                if (player instanceof LocalPlayer) { // Temporary workaround
                    LocalPlayer localPlayer = (LocalPlayer) player;

                    Tool tool = session.getTool(player.getItemInHand());
                    if (tool != null && tool instanceof DoubleActionTraceTool) {
                        if (tool.canUse(localPlayer)) {
                            ((DoubleActionTraceTool) tool).actSecondary(getServerInterface(), getConfiguration(), localPlayer, session);
                            event.setCancelled(true);
                            return;
                        }
                    }
                }

                break;
            }

            case SECONDARY: {
                if (player.getItemInHand() == getConfiguration().navigationWand) {
                    if (getConfiguration().navigationWandMaxDistance <= 0) {
                        return;
                    }

                    if (!player.hasPermission("worldedit.navigation.thru.tool")) {
                        return;
                    }

                    if (!player.passThroughForwardWall(40)) {
                        player.printError("Nothing to pass through!");
                    }

                    event.setCancelled(true);
                    return;
                }

                LocalSession session = worldEdit.getSessionManager().get(player);

                if (player instanceof LocalPlayer) { // Temporary workaround
                    LocalPlayer localPlayer = (LocalPlayer) player;

                    Tool tool = session.getTool(player.getItemInHand());
                    if (tool != null && tool instanceof TraceTool) {
                        if (tool.canUse(localPlayer)) {
                            ((TraceTool) tool).actPrimary(getServerInterface(), getConfiguration(), localPlayer, session);
                            event.setCancelled(true);
                            return;
                        }
                    }
                }

                break;
            }
        }
    }

    /**
     * A default configuration for when none is set.
     */
    private static class DefaultConfiguration extends LocalConfiguration {
        @Override
        public void load() {
        }
    }

}
