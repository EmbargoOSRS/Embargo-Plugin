package gg.embargo.commands;

import com.google.gson.JsonObject;
import gg.embargo.DataManager;
import gg.embargo.EmbargoConfig;
import gg.embargo.commands.embargo.Rank;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.eventbus.EventBus;

import javax.inject.Inject;
import java.awt.*;

@Slf4j
public class CommandManager {
    @Inject
    private ClientThread clientThread;

    @Inject
    private EmbargoConfig config;

    @Inject
    private Client client;

    @Inject
    private DataManager dataManager;

    @Inject
    private ChatCommandManager chatCommandManager;

    private final EventBus eventBus;

    private static final String EMBARGO_COMMAND = "!embargo";

    @Inject
    public CommandManager(Client client, ClientThread clientThread, EventBus eventBus, EmbargoConfig config) {
        this.client = client;
        this.clientThread = clientThread;
        this.eventBus = eventBus;
        this.config = config;
    }

    public void startUp() {
        eventBus.register(this);
        chatCommandManager.registerCommandAsync(EMBARGO_COMMAND, this::processEmbargoLookupChatCommand);
    }

    public void shutDown() {
        eventBus.unregister(this);
        chatCommandManager.unregisterCommand(EMBARGO_COMMAND);
    }

    // Helper method to update chat message safely on the client thread
    private void updateChatMessage(ChatMessage chatMessage, String message) {
        clientThread.invokeLater(() -> {
            chatMessage.getMessageNode().setRuneLiteFormatMessage(message);
            client.refreshChat();
        });
    }

    // Helper method to build error messages
    private String buildErrorMessage(String errorText) {
        return new ChatMessageBuilder()
                .append(ChatColorType.HIGHLIGHT)
                .append(errorText)
                .build();
    }

    public void processEmbargoLookupChatCommand(ChatMessage chatMessage, String message) {
        int firstWhitespace = message.indexOf(' ');
        String memberName = (firstWhitespace != -1 && firstWhitespace + 1 < message.length())
                ? message.substring(firstWhitespace + 1)
                : chatMessage.getName().replaceAll("<[^>]*>", "");

        updateChatMessage(chatMessage, buildErrorMessage("Looking up Embargo member..."));

        String finalMemberName = memberName.replace('\u00A0', ' ').trim();
        dataManager.getProfileAsync(finalMemberName, true).thenAccept(embargoProfileData -> {
            // Cache JSON object to avoid duplicate lookups
            JsonObject leaderboardRank = embargoProfileData != null ? embargoProfileData.getAsJsonObject("leaderboardRank") : null;

            // Null checks for safety
            if (embargoProfileData == null
                    || embargoProfileData.get("accountPoints") == null
                    || embargoProfileData.getAsJsonPrimitive("communityPoints") == null
                    || embargoProfileData.getAsJsonPrimitive("currentRank") == null
                    || leaderboardRank == null) {
                updateChatMessage(chatMessage, buildErrorMessage("Error retrieving data for member: " + finalMemberName));
                return;
            }

            String currentRankName = embargoProfileData.getAsJsonPrimitive("currentRank").getAsString();
            String leaderboardPosition = leaderboardRank.get("currentPosition") + "/" + leaderboardRank.get("totalPositions");
            Color rankColor = Rank.getColorByName(currentRankName);
            Color labelColor = config.chatCommandOutputColor();

            String outputMessage = new ChatMessageBuilder()
                    .append(labelColor, "Member: ")
                    .append(finalMemberName)
                    .append(labelColor, " Rank: ")
                    .append(rankColor, currentRankName)
                    .append(labelColor, " Account Points: ")
                    .append(ChatColorType.HIGHLIGHT)
                    .append(String.valueOf(embargoProfileData.get("accountPoints")))
                    .append(labelColor, " Community Points: ")
                    .append(ChatColorType.HIGHLIGHT)
                    .append(String.valueOf(embargoProfileData.getAsJsonPrimitive("communityPoints")))
                    .append(labelColor, " Leaderboard Rank: ")
                    .append(ChatColorType.HIGHLIGHT)
                    .append(leaderboardPosition)
                    .build();

            updateChatMessage(chatMessage, outputMessage);
        }).exceptionally(ex -> {
            updateChatMessage(chatMessage, buildErrorMessage("Member " + finalMemberName + " not found."));
            return null;
        });
    }

}
