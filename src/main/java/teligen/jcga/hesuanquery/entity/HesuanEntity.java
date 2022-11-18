package teligen.jcga.hesuanquery.entity;

import cn.hutool.core.date.DateUtil;

import java.util.Date;

public class HesuanEntity implements Comparable<HesuanEntity> {

    public String collectionTime;
    public String appointmentCollectionName;
    public String sampling;
    public String cardNo;
    public String testCompleteStatus;
    public String name;
    public String testCompleteTime;
    public String receiveTestPointName;

    public String getCollectionTime() {
        return collectionTime;
    }

    public void setCollectionTime(String collectionTime) {
        this.collectionTime = collectionTime;
    }

    public String getAppointmentCollectionName() {
        return appointmentCollectionName;
    }

    public void setAppointmentCollectionName(String appointmentCollectionName) {
        this.appointmentCollectionName = appointmentCollectionName;
    }

    public String getSampling() {
        return sampling;
    }

    public void setSampling(String sampling) {
        this.sampling = sampling;
    }

    public String getCardNo() {
        return cardNo;
    }

    public void setCardNo(String cardNo) {
        this.cardNo = cardNo;
    }


    public String getTestCompleteStatus() {
        return testCompleteStatus;
    }

    public void setTestCompleteStatus(String testCompleteStatus) {
        this.testCompleteStatus = testCompleteStatus;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTestCompleteTime() {
        return testCompleteTime;
    }

    public void setTestCompleteTime(String testCompleteTime) {
        this.testCompleteTime = testCompleteTime;
    }

    public String getReceiveTestPointName() {
        return receiveTestPointName;
    }

    public void setReceiveTestPointName(String receiveTestPointName) {
        this.receiveTestPointName = receiveTestPointName;
    }

    @Override
    public int compareTo(HesuanEntity o) {
        return DateUtil.compare(DateUtil.parseDate(o.getCollectionTime()), DateUtil.parseDate(this.getCollectionTime()));
    }
}
