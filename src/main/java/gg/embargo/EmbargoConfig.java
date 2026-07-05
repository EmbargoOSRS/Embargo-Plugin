package gg.embargo;

import net.runelite.client.config.*;

import java.awt.Color;

@ConfigGroup("embargo")
public interface EmbargoConfig extends Config
{
    @ConfigSection(
            name = "Data Syncing",
            description = "Control what data the plugin syncs to Embargo",
            position = 0
    )
    String dataSyncSettings = "DataSyncSettings";

    @ConfigItem(
            keyName = "syncUntrackableItems",
            name = "Sync Untradeable Bank Items",
            description = "When you open your bank, scan it for untradeable items (e.g. Barrows gloves, Book of the dead, capes) "
                    + "that can't be tracked any other way, and sync them to Embargo. Turn this off to disable the bank scan.",
            position = 1,
            section = dataSyncSettings
    )
    default boolean syncUntrackableItems() {
        return true;
    }

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
            name = "Collection Log",
            description = "Settings for syncing your collection log with Embargo",
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

    @ConfigItem(
            keyName = "autoSyncCollectionLog",
            name = "Auto Sync on Open",
            description = "Automatically sync your collection log with Embargo whenever you open it",
            position = 2,
            section = collectionLogSettings
    )
    default boolean autoSyncCollectionLog() { return true; }

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

    @ConfigItem(
            keyName = "embargoMessageColor",
            name = "[Embargo] Tag Color",
            description = "The color of the [Embargo] tag in chat messages",
            position = 5,
            section = chatAlertSettings
    )
    default Color embargoMessageColor() {
        return new Color(255, 144, 0);
    }

    @ConfigSection(
            name = "Bingo",
            description = "Settings for bingo event tracking and display",
            position = 5
    )
    String bingoSettings = "BingoSettings";

    @ConfigItem(
            keyName = "enableBingo",
            name = "Enable Bingo",
            description = "Master switch to enable/disable all bingo functionality including tracking, overlays, and UI",
            position = 0,
            section = bingoSettings
    )
    default boolean enableBingo() {
        return true;
    }

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

    @ConfigItem(
            keyName = "bingoChatPrivacy",
            name = "Hide Chat in Screenshots",
            description = "Whether to hide the chat box and private messages when capturing bingo screenshots",
            position = 4,
            section = bingoSettings
    )
    default ChatPrivacyMode bingoChatPrivacy() {
        return ChatPrivacyMode.HIDE_SPLIT_PM;
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

    @ConfigSection(
            name = "3D Model Uploads",
            description = "Settings for uploading your character's 3D model to your Embargo profile",
            position = 7
    )
    String modelUploadSettings = "ModelUploadSettings";

    @ConfigItem(
            keyName = "enableModelUploads",
            name = "Enable Model Uploads",
            description = "Automatically upload your character's 3D model to your Embargo profile on login and equipment changes",
            position = 0,
            section = modelUploadSettings
    )
    default boolean enableModelUploads() {
        return true;
    }

    @ConfigItem(
            keyName = "includePlayerPet",
            name = "Include Pet Model",
            description = "Also upload your follower pet's 3D model when one is out",
            position = 1,
            section = modelUploadSettings
    )
    default boolean includePlayerPet() {
        return true;
    }

    @ConfigSection(
            name = "Clan Platform",
            description = "Clan announcements, event schedule, and automatic tracking features",
            position = 8
    )
    String clanPlatformSettings = "ClanPlatformSettings";

    @ConfigItem(
            keyName = "showClanAnnouncements",
            name = "Show Announcements",
            description = "Show staff clan announcements in your game chat",
            position = 0,
            section = clanPlatformSettings
    )
    default boolean showClanAnnouncements() {
        return true;
    }

    @ConfigItem(
            keyName = "enablePbTracking",
            name = "Track Personal Bests",
            description = "Automatically submit boss kill times and personal bests to Embargo for clan leaderboards",
            position = 1,
            section = clanPlatformSettings
    )
    default boolean enablePbTracking() {
        return true;
    }

    @ConfigItem(
            keyName = "enablePetAttribution",
            name = "Track Pet Drops",
            description = "Automatically report pet drops and their likely source to Embargo",
            position = 2,
            section = clanPlatformSettings
    )
    default boolean enablePetAttribution() {
        return true;
    }

    @ConfigItem(
            keyName = "enableNameChangeSync",
            name = "Sync Name Changes",
            description = "Report RuneScape name changes seen in your friends/clan lists so the clan roster stays current",
            position = 3,
            section = clanPlatformSettings
    )
    default boolean enableNameChangeSync() {
        return true;
    }

    @ConfigItem(
            keyName = "showLookupMenuOption",
            name = "Right-click Embargo Lookup",
            description = "Add an 'Embargo Lookup' option when right-clicking players to view their profile",
            position = 4,
            section = clanPlatformSettings
    )
    default boolean showLookupMenuOption() {
        return true;
    }

    @ConfigItem(
            keyName = "showEventSchedule",
            name = "Show Event Schedule",
            description = "Show the clan event schedule in the side panel",
            position = 5,
            section = clanPlatformSettings
    )
    default boolean showEventSchedule() {
        return true;
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
            keyName = "eventNotifyMinutes",
            name = "Event Reminder (minutes)",
            description = "How many minutes before a subscribed event starts to send a desktop notification",
            position = 6,
            section = clanPlatformSettings
    )
    default int eventNotifyMinutes() {
        return 10;
    }

}

