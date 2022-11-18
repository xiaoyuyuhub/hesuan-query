package teligen.jcga.hesuanquery.entity;

import cn.hutool.core.util.StrUtil;

import java.util.Objects;

public class ExcelQueryEntity {
    public String xm;
    public String sfzh;

    public String getXm() {
        return xm;
    }

    public void setXm(String xm) {
        this.xm = xm;
    }

    public String getSfzh() {
        return sfzh;
    }

    public void setSfzh(String sfzh) {
        this.sfzh = sfzh;
    }

    public void trim() {
        this.xm = StrUtil.trim(this.xm);
        this.sfzh = StrUtil.trim(this.sfzh);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xm,sfzh);
    }

    @Override
    public boolean equals(Object obj) {

        if (obj instanceof ExcelQueryEntity) {
            ExcelQueryEntity excelQueryEntity = (ExcelQueryEntity) obj;
            if (excelQueryEntity.getXm().equals(this.xm) && excelQueryEntity.getSfzh().equals(this.sfzh)) return true;
            else return false;
        } else {
            return false;
        }
    }
}
