/*
Almost all of this code was taken from the tob-notice-board plugin by Broooklyn
https://github.com/Broooklyn/runelite-external-plugins/tree/tob-notice-board
Modifications were made to work with clans by Sharpienero/Embargo

Added TOA code
 13/04/2025 - Converted to use gamevals
 */


package gg.embargo.noticeboard;

import gg.embargo.EmbargoConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;
import net.runelite.client.callback.ClientThread;


import javax.inject.Inject;

@Slf4j
public class NoticeBoardManager {
    @Inject
    private Client client;

    @Inject
    private EmbargoConfig config;

    @Inject
    private ClientThread clientThread;

    private final EventBus eventBus;



    @Inject
    public NoticeBoardManager(Client client, ClientThread clientThread, EventBus eventBus) {
        this.client = client;
        this.clientThread = clientThread;
        this.eventBus = eventBus;
    }

    private static final int DEFAULT_RGB = 0xff981f;
    private static final int STARTING_PARTY_CHILD_ID = 17;
    private static final int ENDING_PARTY_CHILD_ID = 62;

    private void setNoticeBoardWidget(int parent, int index, int clanColor) {
        for (int childID = STARTING_PARTY_CHILD_ID; childID < ENDING_PARTY_CHILD_ID; ++childID) {
            Widget noticeBoard = client.getWidget(parent, childID);

            if (noticeBoard != null && noticeBoard.getName() != null && noticeBoard.getChildren() != null) {
                for (Widget noticeBoardChild : noticeBoard.getChildren()) {
                    if (noticeBoardChild.getIndex() == index) {
                        if (client.getClanChannel() != null) {
                            for (ClanChannelMember member : client.getClanChannel().getMembers()) {
                                if (Text.toJagexName(member.getName()).equals(Text.removeTags(noticeBoard.getName()))) {
                                    noticeBoardChild.setTextColor(config.highlightClan() ? clanColor : DEFAULT_RGB);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void setApplicationWidget(int parent, int child, int clanColor) {
        Widget acceptWidgetMembers = client.getWidget(parent, child);
        if (acceptWidgetMembers != null && acceptWidgetMembers.getChildren() != null) {
            Widget[] acceptWidgetChildren = acceptWidgetMembers.getChildren();
            for (Widget w : acceptWidgetChildren) {
                if (client != null && client.getClanChannel() != null) {
                    for (ClanChannelMember member : client.getClanChannel().getMembers()) {
                        if (w.getText().contains(member.getName())) {
                            String hex = Integer.toHexString(clanColor).substring(2);
                            String builtName = "<col=" + hex + ">" + member.getName() + "</col>";
                            w.setName("<col=" + hex + ">" + member.getName() + "</col>");
                            w.setText(builtName);
                        }
                    }

                }
            }
        }
    }

    private void setTOBNameColors(int clanColor) {
        setApplicationWidget(InterfaceID.TOB_PARTYDETAILS, 42, clanColor);
        setNoticeBoardWidget(InterfaceID.TOB_PARTYLIST, 3, clanColor);
    }

    private void setTOANameColors(int clanColor) {
        setApplicationWidget(InterfaceID.TOA_PARTYDETAILS, 48, clanColor);
        setNoticeBoardWidget(InterfaceID.TOA_PARTYLIST, 2, clanColor);
    }

    public void startUp()
    {
        eventBus.register(this);
    }
    public void shutDown()
    {
        unsetNoticeBoards();
        eventBus.unregister(this);
    }

    public void setTOBNoticeBoard()
    {

        setTOBNameColors(config.clanColor().getRGB());
    }

    public void setTOANoticeBoard() {
        setTOANameColors(config.clanColor().getRGB());
    }

    public void setNoticeBoards() {
        if (config.highlightClan()) {
            setTOBNoticeBoard();
            setTOANoticeBoard();
        }
    }

    public void unsetNoticeBoards()
    {
        setTOBNameColors(DEFAULT_RGB);
        setTOANameColors(DEFAULT_RGB);
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded widgetLoaded)
    {
        clientThread.invokeLater(() ->
        {
            // TOB
            if (widgetLoaded.getGroupId() == InterfaceID.TOB_PARTYLIST || widgetLoaded.getGroupId() == InterfaceID.TOB_PARTYDETAILS)
            {
                setTOBNoticeBoard();
            }

            // TOA
            if (widgetLoaded.getGroupId() == InterfaceID.TOA_PARTYLIST|| widgetLoaded.getGroupId() == InterfaceID.TOA_PARTYDETAILS) {
                setTOANoticeBoard();
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        String CONFIG_GROUP = "embargo";
        if (!event.getGroup().equals(CONFIG_GROUP))
        {
            return;
        }

        unsetNoticeBoards();
        if (config.highlightClan()) {
            setTOBNoticeBoard();
            setTOANoticeBoard();
        }
    }
}
