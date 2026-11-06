package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.Game;

public interface GameAccessProbe {
    GameAccessState check(Game game);
}
