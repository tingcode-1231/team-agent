CREATE TABLE IF NOT EXISTS candidate (
                                         id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
                                         name         VARCHAR(32)  NOT NULL COMMENT '姓名',
                                         school       VARCHAR(64)  DEFAULT NULL,
                                         major        VARCHAR(64)  DEFAULT NULL,
                                         grade        VARCHAR(16)  NOT NULL COMMENT '大一/大二/大三/大四/研一/研二',
                                         skills       VARCHAR(255) NOT NULL COMMENT '英文逗号分隔，如 Java,MySQL',
                                         competitions VARCHAR(255) DEFAULT NULL COMMENT '参加过的竞赛',
                                         free_days    TINYINT      NOT NULL DEFAULT 0 COMMENT '每周可投入天数',
                                         intro        VARCHAR(255) DEFAULT NULL,
                                         created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                         KEY idx_grade (grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选人库';