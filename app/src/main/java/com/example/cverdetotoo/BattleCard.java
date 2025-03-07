package com.example.cverdetotoo;

public class BattleCard {

    private CardType type;
    private int effectValue;   // For healing/damage amounts.
    private String fact;       // The card's description/name.
    private int imageResId;    // Drawable resource for the card image.

    public BattleCard(CardType type, int effectValue, String fact, int imageResId) {
        this.type = type;
        this.effectValue = effectValue;
        this.fact = fact;
        this.imageResId = imageResId;
    }

    public CardType getType() {
        return type;
    }

    public int getEffectValue() {
        return effectValue;
    }

    public String getFact() {
        return fact;
    }

    public int getImageResId() {
        return imageResId;
    }

    // This method is used as the card's "name" in the game.
    public String getName() {
        return fact;
    }
}
