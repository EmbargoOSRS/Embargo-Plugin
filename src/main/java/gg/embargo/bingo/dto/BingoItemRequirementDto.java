package gg.embargo.bingo.dto;

/**
 * DTO for item requirements from the API.
 */
public class BingoItemRequirementDto {
    public int id;
    public Integer itemGroupId;
    public int itemId;
    public String itemName;
    public int requiredQuantity = 1;
    public boolean isAlternative;
    public String source;
}
