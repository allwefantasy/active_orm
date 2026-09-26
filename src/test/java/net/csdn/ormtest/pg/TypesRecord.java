package net.csdn.ormtest.pg;

import net.csdn.jpa.model.Model;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

public class TypesRecord extends Model {
    private Integer id;
    private Long bigValue;
    private Boolean flag;
    private BigDecimal amount;
    private Date happenedAt;
    private Date day;
    private byte[] payload;
    private UUID token;

    public Integer getId() {
        return id;
    }

    public Long getBigValue() {
        return bigValue;
    }

    public void setBigValue(Long bigValue) {
        this.bigValue = bigValue;
    }

    public Boolean getFlag() {
        return flag;
    }

    public void setFlag(Boolean flag) {
        this.flag = flag;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Date getHappenedAt() {
        return happenedAt;
    }

    public void setHappenedAt(Date happenedAt) {
        this.happenedAt = happenedAt;
    }

    public Date getDay() {
        return day;
    }

    public void setDay(Date day) {
        this.day = day;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public UUID getToken() {
        return token;
    }

    public void setToken(UUID token) {
        this.token = token;
    }
}
