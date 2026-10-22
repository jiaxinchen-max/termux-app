package com.termux.ui.data;

import com.termux.ui.domain.Game;

public interface GameAccessProbe {
    GameAccessState check(Game game);
}
