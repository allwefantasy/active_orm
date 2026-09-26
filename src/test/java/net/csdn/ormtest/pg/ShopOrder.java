package net.csdn.ormtest.pg;

import net.csdn.jpa.model.Model;

import javax.persistence.ManyToOne;

public class ShopOrder extends Model {
    private Integer id;
    private String status;
    private String region;

    @ManyToOne
    private Customer customer;

    public Integer getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }
}
