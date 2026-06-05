package com.mediquery.common.constant;

/**
 * 系统常量：角色、收藏类型、搜索历史类型
 */
public class MedicalConstants {
    private MedicalConstants() {}

    public static final int HISTORY_TYPE_SEARCH = 1;
    public static final int HISTORY_TYPE_ILLNESS = 2;
    public static final int HISTORY_TYPE_MEDICINE = 3;

    public static final int ROLE_ADMIN = 1;
    public static final int ROLE_USER = 0;

    public static final int FAVORITE_TYPE_ILLNESS = 1;
    public static final int FAVORITE_TYPE_MEDICINE = 2;
}
