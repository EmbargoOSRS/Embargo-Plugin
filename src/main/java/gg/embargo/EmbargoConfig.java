package gg.embargo;

import net.runelite.client.config.*;

import java.awt.Color;

@ConfigGroup("embargo")
public interface EmbargoConfig extends Config
{
    @ConfigSection(
            name = "Raid Notice Boards",
            description = "Section that houses Notice Board options",
            position = 1
    )
    String noticeBoardSettings = "NoticeBoardSettings";

    @ConfigItem(
            keyName = "highlightClan",
            name = "Highlight Embargo Members",
            description = "Whether or not to highlight clan chat members' names on notice boards (ToA, Tob)",
            position = 1,
            section = noticeBoardSettings
    )
    default boolean highlightClan()
    {
        return true;
    }

    @ConfigItem(
            keyName = "clanColor",
            name = "Highlight Color",
            description = "The color with which to highlight names from your current clan chat",
            position = 2,
            section = noticeBoardSettings
    )
    default Color clanColor()
    {
        return new Color(53, 201, 255);
    }

    @ConfigSection(
            name = "Collection Log Sync Button",
            description = "Add a button to the collection log interface to sync your collection log with Embargo",
            position = 2
    )
    String collectionLogSettings = "CollectionLogSettings";

    @ConfigItem(
            keyName = "showCollectionLogSyncButton",
            name = "Show Collection Log Sync Button",
            description = "Whether or not to render the Embargo collection log sync button",
            position = 1,
            section = collectionLogSettings
    )
    default boolean showCollectionLogSyncButton() { return true; }

    @ConfigSection(
            name = "Clan Easter Eggs",
            description = "Enables fun item name replacements like 'Dragon warhammer' to 'Bonker'",
            position = 3
    )
    String easterEggSettings = "EasterEggSettings";

    @ConfigItem(
        keyName = "enableClanEasterEggs",
        name = "Enable Easter Eggs",
        description = "A top level control to enable/disable the feature",
        position = 3,
        section = easterEggSettings
    )
    default boolean enableClanEasterEggs() {
        return true;
    }


    @ConfigItem(
            keyName = "enableItemRenames",
            name = "Enable Item Renames",
            description = "Enables item name replacements like 'Dragon warhammer' to 'Bonker'",
            position = 4,
            section = easterEggSettings
    )
    default boolean enableItemRenames() {
        return true;
    }

    @ConfigItem(
            keyName = "enableNpcRenames",
            name = "Enable NPC Renames",
            description = "Enables NPC name changes, like 'Pestilent Bloat' to 'Dr D1sconnect'",
            position = 5,
            section = easterEggSettings
    )
    default boolean enableNpcRenames() {
        return true;
    }

    @ConfigSection(
            name = "Chat Alerts",
            description = "Control which notifications appear in your chat box",
            position = 4
    )
    String chatAlertSettings = "ChatAlertSettings";

    @ConfigItem(
            keyName = "enableBountyAlerts",
            name = "Bounty Alerts",
            description = "Show a chat message when a new bounty becomes active",
            position = 1,
            section = chatAlertSettings
    )
    default boolean enableBountyAlerts() {
        return true;
    }

    @ConfigItem(
            keyName = "enableEventAlerts",
            name = "Event Alerts",
            description = "Show a chat message when a new Of The Week event starts",
            position = 2,
            section = chatAlertSettings
    )
    default boolean enableEventAlerts() {
        return true;
    }

    @ConfigItem(
            keyName = "enablePollAlerts",
            name = "Poll Alerts",
            description = "Show a chat message when a new poll is available",
            position = 3,
            section = chatAlertSettings
    )
    default boolean enablePollAlerts() {
        return true;
    }

    @ConfigItem(
            keyName = "enableBingoAlerts",
            name = "Bingo Alerts",
            description = "Show chat messages for bingo events, tile completions, and status updates",
            position = 4,
            section = chatAlertSettings
    )
    default boolean enableBingoAlerts() {
        return true;
    }

    @ConfigSection(
            name = "Bingo",
            description = "Settings for bingo event tracking and display",
            position = 5
    )
    String bingoSettings = "BingoSettings";

    @ConfigItem(
            keyName = "enableBingoTracking",
            name = "Enable Bingo Tracking",
            description = "Automatically track bingo tile drops and submit them to the server. Disabling this will notify administrators.",
            position = 1,
            section = bingoSettings
    )
    default boolean enableBingoTracking() {
        return true;
    }

    @ConfigItem(
            keyName = "showBingoCodeword",
            name = "Show Bingo Codeword",
            description = "Display the secret bingo codeword overlay when enrolled in an active bingo",
            position = 2,
            section = bingoSettings
    )
    default boolean showBingoCodeword() {
        return true;
    }

    @ConfigItem(
            keyName = "bingoScreenshots",
            name = "Auto-capture Screenshots",
            description = "Automatically capture and upload screenshots when obtaining bingo tile items",
            position = 3,
            section = bingoSettings
    )
    default boolean bingoScreenshots() {
        return true;
    }

    @ConfigSection(
            name = "Chat Commands",
            description = "Section that houses Chat Command options",
            position = 6
    )
    String chatCommandSettings = "ChatCommandSettings";

    @ConfigItem(
            keyName = "chatCommandOutputColor",
            name = "Output Text Color",
            description = "The color that highlighted text will be when using clan chat commands.",
            position = 1,
            section = chatCommandSettings
    )
    default Color chatCommandOutputColor()
    {
        return new Color(255, 116, 0);
    }

}

