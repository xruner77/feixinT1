#!/system/bin/sh
# 斐讯 T1 影院级 10-bit + BT.2390 极致画质注入脚本

# 1. 临时放行权限
setenforce 0 2>/dev/null

# 2. 激活 4K 60Hz 10-bit 色深通道 (仅在未处于 cd=5 时触发重协商，避免重复闪屏)
echo 420,10bit > /sys/class/amhdmitx/amhdmitx0/attr
if ! cat /sys/class/amhdmitx/amhdmitx0/config | grep -q "cd = 5"; then
    echo 31183118 | su 1000 sh -c 'echo 2160p60hz420 > /sys/class/display/mode' 2>/dev/null
    sleep 1
fi

# 3. 注入 HDR -> SDR 映射开关与 Ultra Punch 35 点曲线
echo 1 > /sys/module/am_vecm/parameters/hdr_mode
echo 0,16,46,76,107,137,168,198,228,306,388,444,489,529,565,599,630,660,688,714,740,765,789,812,834,856,877,898,919,938,958,977,996,1014,1023 > /sys/module/am_vecm/parameters/knee_setting
echo 1 > /sys/module/am_vecm/parameters/knee_interpolation_mode
echo 256 > /sys/module/am_vecm/parameters/knee_factor
echo 0 > /sys/module/am_vecm/parameters/range_control
echo 50 > /sys/module/am_vecm/parameters/sdr_saturation_offset

# 4. 全开硬件直方图与白场拉伸 (DNLP White Extension)
echo 0x1 > /sys/class/amvecm/dnlp
echo 1 > /sys/module/am_vecm/parameters/dnlp_en
echo 1 > /sys/module/am_vecm/parameters/dnlp_en_2
echo 8 > /sys/module/am_vecm/parameters/dnlp_adj_level
echo 8 > /sys/module/am_vecm/parameters/ve_dnlp_adj_level
echo 255 > /sys/module/am_vecm/parameters/ve_dnlp_strength
echo 16 > /sys/module/am_vecm/parameters/ve_dnlp_whtext
echo 1 > /sys/module/am_vecm/parameters/ve_dnlp_set_wext
echo blk_ext_en > /sys/class/amvecm/pq_user_set

# 5. 高光放大 1.10x 矩阵系数
echo "2760,-507,0,-95,2347,0,-44,-177,2474,1" > /sys/module/am_vecm/parameters/video_eotf_coeff
echo 1 > /sys/module/am_vecm/parameters/reload_lut
echo 1 > /sys/module/am_vecm/parameters/reload_mtx

# 6. 极致反差对比度 50 (最后写入，防止驱动覆盖)
echo 50 > /sys/class/video/contrast
