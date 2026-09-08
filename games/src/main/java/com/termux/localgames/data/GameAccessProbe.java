package com.termux.localgames.data;

import com.termux.localgames.domain.Game;

public interface GameAccessProbe {
    GameAccessState check(Game game);
}
