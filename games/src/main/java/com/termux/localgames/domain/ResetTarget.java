package com.termux.localgames.domain;

/** Which independent runtime environment a reset task tears down. */
public enum ResetTarget {
    GLIBC,
    ROOTFS
}
