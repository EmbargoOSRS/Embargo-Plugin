package gg.embargo.bingo.dto;

import gg.embargo.bingo.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mapper to convert DTOs to domain objects.
 */
public class BingoMapper {

    public static BingoState toState(BingoStateDto dto) {
        if (dto == null) {
            return null;
        }

        Map<Integer, BingoTile> tiles = new HashMap<>();
        if (dto.tiles != null) {
            for (BingoTileDto tileDto : dto.tiles.values()) {
                BingoTile tile = toTile(tileDto);
                if (tile != null) {
                    tiles.put(tile.getId(), tile);
                }
            }
        }

        Map<Integer, BingoTeamTileProgress> teamProgress = new HashMap<>();
        if (dto.teamProgress != null) {
            for (BingoTeamTileProgressDto progressDto : dto.teamProgress) {
                BingoTeamTileProgress progress = toProgress(progressDto);
                if (progress != null) {
                    teamProgress.put(progress.getBingoTileId(), progress);
                }
            }
        }

        return BingoState.builder()
                .id(dto.id)
                .name(dto.name != null ? dto.name : "")
                .description(dto.description != null ? dto.description : "")
                .size(dto.size > 0 ? dto.size : 5)
                .startDate(parseInstant(dto.startDate))
                .endDate(parseInstant(dto.endDate))
                .status(dto.status != null ? dto.status : "")
                .codeword(dto.codeword)
                .tiles(tiles)
                .userTeam(toTeam(dto.userTeam))
                .teamProgress(teamProgress)
                .itemIdToTileIds(BingoState.buildItemLookup(tiles))
                .build();
    }

    public static BingoTile toTile(BingoTileDto dto) {
        if (dto == null) {
            return null;
        }

        List<BingoItemRequirement> itemRequirements = dto.itemRequirements != null
                ? dto.itemRequirements.stream()
                    .map(BingoMapper::toItemRequirement)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
                : Collections.emptyList();

        List<BingoItemGroup> itemGroups = dto.itemGroups != null
                ? dto.itemGroups.stream()
                    .map(BingoMapper::toItemGroup)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return BingoTile.builder()
                .id(dto.id)
                .bingoBoardId(dto.bingoBoardId)
                .position(dto.position)
                .title(dto.title != null ? dto.title : "")
                .description(dto.description != null ? dto.description : "")
                .imageUrl(dto.imageUrl)
                .wikiKey(dto.wikiKey)
                .points(dto.points > 0 ? dto.points : 1)
                .tileType(BingoTileType.fromValue(dto.tileType))
                .requiredCount(dto.requiredCount > 0 ? dto.requiredCount : 1)
                .itemRequirements(itemRequirements)
                .itemGroups(itemGroups)
                .build();
    }

    public static BingoItemRequirement toItemRequirement(BingoItemRequirementDto dto) {
        if (dto == null) {
            return null;
        }

        return BingoItemRequirement.builder()
                .id(dto.id)
                .itemGroupId(dto.itemGroupId)
                .itemId(dto.itemId)
                .itemName(dto.itemName != null ? dto.itemName : "")
                .requiredQuantity(dto.requiredQuantity > 0 ? dto.requiredQuantity : 1)
                .isAlternative(dto.isAlternative)
                .source(dto.source)
                .build();
    }

    public static BingoItemGroup toItemGroup(BingoItemGroupDto dto) {
        if (dto == null) {
            return null;
        }

        List<BingoItemRequirement> items = dto.items != null
                ? dto.items.stream()
                    .map(BingoMapper::toItemRequirement)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
                : Collections.emptyList();

        return BingoItemGroup.builder()
                .id(dto.id)
                .bingoTileId(dto.bingoTileId)
                .groupName(dto.groupName != null ? dto.groupName : "")
                .requiredCount(dto.requiredCount > 0 ? dto.requiredCount : 1)
                .sortOrder(dto.sortOrder)
                .items(items)
                .build();
    }

    public static BingoTeam toTeam(BingoTeamDto dto) {
        if (dto == null) {
            return null;
        }

        List<String> members = new ArrayList<>();
        if (dto.members != null) {
            for (Object member : dto.members) {
                if (member instanceof String) {
                    members.add((String) member);
                } else if (member instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> memberMap = (Map<String, Object>) member;
                    Object rsn = memberMap.get("rsn");
                    if (rsn != null) {
                        members.add(rsn.toString());
                    }
                }
            }
        }

        return BingoTeam.builder()
                .id(dto.id)
                .bingoBoardId(dto.bingoBoardId)
                .name(dto.name != null ? dto.name : "")
                .colorHex(dto.colorHex)
                .totalPoints(dto.totalPoints)
                .completedTiles(dto.completedTiles)
                .partialTiles(dto.partialTiles)
                .members(members)
                .build();
    }

    public static BingoTeamTileProgress toProgress(BingoTeamTileProgressDto dto) {
        if (dto == null) {
            return null;
        }

        return BingoTeamTileProgress.builder()
                .id(dto.id)
                .teamBingoBoardId(dto.teamBingoBoardId)
                .bingoTileId(dto.bingoTileId)
                .status(BingoTileStatus.fromValue(dto.status))
                .currentCount(dto.currentCount)
                .proofUrls(dto.proofUrls != null ? dto.proofUrls : Collections.emptyList())
                .notes(dto.notes)
                .completedAt(parseInstant(dto.completedAt))
                .completedByRsn(dto.completedByRsn)
                .build();
    }

    public static BingoCompletionEvent toCompletionEvent(BingoCompletionDto dto) {
        if (dto == null) {
            return null;
        }

        return BingoCompletionEvent.builder()
                .id(dto.id)
                .bingoBoardId(dto.bingoBoardId)
                .tileId(dto.tileId)
                .tileTitle(dto.tileTitle != null ? dto.tileTitle : "")
                .teamId(dto.teamId)
                .teamName(dto.teamName != null ? dto.teamName : "")
                .completedByRsn(dto.completedByRsn != null ? dto.completedByRsn : "")
                .completedAt(parseInstant(dto.completedAt))
                .pointsAwarded(dto.pointsAwarded)
                .completionType(dto.completionType != null ? dto.completionType : "")
                .screenshotUrl(dto.screenshotUrl)
                .build();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
