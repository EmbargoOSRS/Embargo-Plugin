/*
 * Copyright (c) 2025, andmcadams
 * Modified by Sharpienero for Embargo
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
 */

package gg.embargo.manifest;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Data
public class Manifest
{
    // misc
    final float version = -1;

    //Varp/varb trackable items
    final int[] varbits = new int[0];
    final int[] varps = new int[0];

    //Untrackable items
    public final List<Integer> untrackableItems = new ArrayList<>();

    //Raid/Minigame completion messages
    public Map<String, String> raidCompletionMessages = new HashMap<>();
    public Map<String, String> minigameCompletionMessages = new HashMap<>();

    //Collection log
    public final ArrayList<Integer> collections = new ArrayList<>();

    //Easter egg
    public Map<String, String> itemRenames = new HashMap<>();
    public Map<String, String> npcRenames = new HashMap<>();

    // Remote feature kill switches: a feature key mapped to false disables
    // that feature for all clients without a plugin release. Missing keys
    // mean enabled.
    public Map<String, Boolean> features = new HashMap<>();

    // Staff announcements shown in-game as broadcast chat lines
    public List<Announcement> announcements = new ArrayList<>();

    // Scheduled clan events rendered in the side panel
    public List<ClanEvent> schedule = new ArrayList<>();

}