package com.termux.localgames.domain;

/** Which independent runtime environment a reset task tears down. RootFS has no reset of its
 *  own anymore -- "rebuild" (delete the shared base archive + rebuild it) fully replaced it, see
 *  LocalGamesActivity.confirmRootfsRebuild(). */
public enum ResetTarget {
    GLIBC
}
