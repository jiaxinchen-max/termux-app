package com.termux.display.controller.xserver;

public enum XKeycode {
    KEY_NONE(0),
    KEY_ESC(9),
    KEY_1(10),
    KEY_2(11),
    KEY_3(12),
    KEY_4(13),
    KEY_5(14),
    KEY_6(15),
    KEY_7(16),
    KEY_8(17),
    KEY_9(18),
    KEY_0(19),
    KEY_MINUS(20),
    KEY_EQUAL(21),
    KEY_BKSP(22),
    KEY_TAB(23),
    KEY_Q(24),
    KEY_W(25),
    KEY_E(26),
    KEY_R(27),
    KEY_T(28),
    KEY_Y(29),
    KEY_U(30),
    KEY_I(31),
    KEY_O(32),
    KEY_P(33),
    KEY_BRACKET_LEFT(34),
    KEY_BRACKET_RIGHT(35),
    KEY_ENTER(36),
    KEY_CTRL_L(37),
    KEY_A(38),
    KEY_S(39),
    KEY_D(40),
    KEY_F(41),
    KEY_G(42),
    KEY_H(43),
    KEY_J(44),
    KEY_K(45),
    KEY_L(46),
    KEY_SEMICOLON(47),
    KEY_APOSTROPHE(48),
    KEY_GRAVE(49),
    KEY_SHIFT_L(50),
    KEY_BACKSLASH(51),
    KEY_Z(52),
    KEY_X(53),
    KEY_C(54),
    KEY_V(55),
    KEY_B(56),
    KEY_N(57),
    KEY_M(58),
    KEY_COMMA(59),
    KEY_PERIOD(60),
    KEY_SLASH(61),
    KEY_SHIFT_R(62),
    KEY_KP_MULTIPLY(63),
    KEY_ALT_L(64),
    KEY_SPACE(65),
    KEY_CAPS_LOCK(66),
    KEY_F1(67),
    KEY_F2(68),
    KEY_F3(69),
    KEY_F4(70),
    KEY_F5(71),
    KEY_F6(72),
    KEY_F7(73),
    KEY_F8(74),
    KEY_F9(75),
    KEY_F10(76),
    KEY_NUM_LOCK(77),
    KEY_SCROLL_LOCK(78),
    KEY_KP_7(79),
    KEY_KP_8(80),
    KEY_KP_9(81),
    KEY_KP_SUBTRACT(82),
    KEY_KP_4(83),
    KEY_KP_5(84),
    KEY_KP_6(85),
    KEY_KP_ADD(86),
    KEY_KP_1(87),
    KEY_KP_2(88),
    KEY_KP_3(89),
    KEY_KP_0(90),
    KEY_KP_DEL(91),
    KEY_F11(95),
    KEY_F12(96),
    KEY_KP_ENTER(104),
    KEY_CTRL_R(105),
    KEY_KP_DIVIDE(106),
    KEY_PRTSCN(107),
    KEY_ALT_R(108),
    KEY_HOME(110),
    KEY_UP(111),
    KEY_PRIOR(112),
    KEY_LEFT(113),
    KEY_RIGHT(114),
    KEY_END(115),
    KEY_DOWN(116),
    KEY_NEXT(117),
    KEY_INSERT(118),
    KEY_DEL(119),
    KEY_MAX(KEY_DEL.id);
    public final byte id;

    XKeycode(int id) {
        this.id = (byte)id;
    }
}
