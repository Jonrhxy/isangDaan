package com.example.cverdetotoo;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

public class GameFragment extends Fragment {

    private CardView cardBattleEco, cardGoGreen, cardCaptureCarbon;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment (note: using fragment_game.xml)
        View view = inflater.inflate(R.layout.activity_game, container, false);

        // Initialize the CardViews
        cardBattleEco = view.findViewById(R.id.cardBattleEco);
        cardGoGreen = view.findViewById(R.id.cardGoGreen);
        cardCaptureCarbon = view.findViewById(R.id.cardCaptureCarbon);

        // Set click listeners for each CardView
        cardBattleEco.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), BattleEcoStart.class);
            startActivity(intent);
        });

        cardGoGreen.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), IntroActivity.class);
            startActivity(intent);
        });

        cardCaptureCarbon.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), CaptureFootprintstart.class);
            startActivity(intent);
        });

        return view;
    }
}
