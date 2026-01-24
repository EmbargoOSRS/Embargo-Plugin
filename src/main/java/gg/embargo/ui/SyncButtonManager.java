/*
 * Copyright (c) 2025, andmcadams
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Thanks to RuneProfile (https://github.com/ReinhardtR/runeprofile-plugin) for Hamburger Button logic
 */

package gg.embargo.ui;

import com.google.inject.Inject;
import gg.embargo.EmbargoConfig;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import java.util.*;

import static java.lang.Math.round;

@Slf4j
public class SyncButtonManager {

    private static final int DRAW_BURGER_MENU = 7812;
    private static final int FONT_COLOR = 0xFF981F;
    private static final int FONT_COLOR_ACTIVE = 0xFFFFFF;
    private static final String BUTTON_TEXT = "Embargo";
    private static final int COOLDOWN_TICKS = 50; // ~30 seconds

    private final Client client;
    private final EventBus eventBus;
    private final EmbargoConfig config;

    private int baseMenuHeight = -1;
    private int lastAttemptedSync = -1;

    @Getter
    @Setter
    private boolean syncAllowed;

    @Inject
    private SyncButtonManager(
            Client client,
            EventBus eventBus,
            EmbargoConfig config) {
        this.client = client;
        this.eventBus = eventBus;
        this.config = config;
    }

    public void startUp() {
        setSyncAllowed(false);
        eventBus.register(this);
    }

    private String getEmbargoTag() {
        java.awt.Color color = config.embargoMessageColor();
        String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        return "<col=" + hex + ">[Embargo]</col>";
    }

    public void shutDown() {
        eventBus.unregister(this);
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (!config.showCollectionLogSyncButton() || event.getScriptId() != DRAW_BURGER_MENU) {
            return;
        }

        Object[] args = event.getScriptEvent().getArguments();
        int menuId = (int) args[3];

        try {
            log.debug("Adding Embargo button to burger menu with ID: {}", menuId);
            addButton(menuId, this::onButtonClick);
        } catch (Exception e) {
            log.debug("Failed to add Embargo button to menu: {}", e.getMessage());
        }
    }

    private void onButtonClick() {
        // Check cooldown
        if (lastAttemptedSync != -1 && lastAttemptedSync + COOLDOWN_TICKS > client.getTickCount()) {
            int ticksRemaining = lastAttemptedSync + COOLDOWN_TICKS - client.getTickCount();
            int secondsRemaining = (int) round(ticksRemaining * 0.6);
            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    getEmbargoTag() + " Sync on cooldown. Try again in " + secondsRemaining + " seconds.",
                    null);
            return;
        }
        lastAttemptedSync = client.getTickCount();

        // Set sync allowed flag and trigger search to iterate collection log
        setSyncAllowed(true);
        client.menuAction(-1, 40697932, MenuAction.CC_OP, 1, -1, "Search", null);
        client.runScript(2240);

        client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                getEmbargoTag() + " Syncing your collection log...",
                null);
    }

    private void addButton(int menuId, Runnable onClick) throws NullPointerException, NoSuchElementException {
        // Disallow syncing from the adventure log to prevent players from syncing
        // while viewing other players' collection logs via the POH adventure log
        boolean isOpenedFromAdventureLog = client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
        if (isOpenedFromAdventureLog) {
            return;
        }

        Widget menu = Objects.requireNonNull(client.getWidget(menuId));
        Widget[] menuChildren = Objects.requireNonNull(menu.getChildren());

        if (baseMenuHeight == -1) {
            baseMenuHeight = menu.getOriginalHeight();
        }

        // Find the last rectangle and text widgets to copy their styling
        List<Widget> reversedMenuChildren = new ArrayList<>(Arrays.asList(menuChildren));
        Collections.reverse(reversedMenuChildren);

        Widget lastRectangle = reversedMenuChildren.stream()
                .filter(w -> w.getType() == WidgetType.RECTANGLE)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No RECTANGLE widget found in menu"));

        Widget lastText = reversedMenuChildren.stream()
                .filter(w -> w.getType() == WidgetType.TEXT)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No TEXT widget found in menu"));

        final int buttonHeight = lastRectangle.getHeight();
        final int buttonY = lastRectangle.getOriginalY() + buttonHeight;

        // Check if button already exists
        final boolean existingButton = Arrays.stream(menuChildren)
                .anyMatch(w -> w.getText().equals(BUTTON_TEXT));

        if (!existingButton) {
            // Create background rectangle matching the existing menu style
            final Widget background = menu.createChild(WidgetType.RECTANGLE)
                    .setOriginalWidth(lastRectangle.getOriginalWidth())
                    .setOriginalHeight(lastRectangle.getOriginalHeight())
                    .setOriginalX(lastRectangle.getOriginalX())
                    .setOriginalY(buttonY)
                    .setOpacity(lastRectangle.getOpacity())
                    .setFilled(lastRectangle.isFilled());
            background.revalidate();

            // Create text widget with hover effects
            final Widget text = menu.createChild(WidgetType.TEXT)
                    .setText(BUTTON_TEXT)
                    .setTextColor(FONT_COLOR)
                    .setFontId(lastText.getFontId())
                    .setTextShadowed(lastText.getTextShadowed())
                    .setOriginalWidth(lastText.getOriginalWidth())
                    .setOriginalHeight(lastText.getOriginalHeight())
                    .setOriginalX(lastText.getOriginalX())
                    .setOriginalY(buttonY)
                    .setXTextAlignment(lastText.getXTextAlignment())
                    .setYTextAlignment(lastText.getYTextAlignment());

            text.setHasListener(true);
            text.setOnMouseOverListener((JavaScriptCallback) ev -> text.setTextColor(FONT_COLOR_ACTIVE));
            text.setOnMouseLeaveListener((JavaScriptCallback) ev -> text.setTextColor(FONT_COLOR));
            text.setAction(0, "Sync your collection log with Embargo");
            text.setOnOpListener((JavaScriptCallback) ev -> onClick.run());
            text.revalidate();
        }

        // Expand the menu height to accommodate the new button
        if (menu.getOriginalHeight() <= baseMenuHeight) {
            menu.setOriginalHeight(menu.getOriginalHeight() + buttonHeight);
        }

        menu.revalidate();
        for (Widget child : menuChildren) {
            child.revalidate();
        }
    }
}
