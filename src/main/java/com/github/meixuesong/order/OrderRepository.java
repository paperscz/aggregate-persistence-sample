package com.github.meixuesong.order;

import com.github.meixuesong.aggregatepersistence.Aggregate;
import com.github.meixuesong.aggregatepersistence.AggregateFactory;
import com.github.meixuesong.aggregatepersistence.DataObjectUtils;
import com.github.meixuesong.customer.CustomerRepository;
import com.github.meixuesong.order.dao.OrderDO;
import com.github.meixuesong.order.dao.OrderDOMapper;
import com.github.meixuesong.order.dao.OrderItemDO;
import com.github.meixuesong.order.dao.OrderItemDOMapper;
import com.github.meixuesong.order.domain.Order;
import com.github.meixuesong.order.domain.OrderItem;
import com.github.meixuesong.product.Product;
import com.github.meixuesong.product.ProductRepository;
import org.springframework.stereotype.Repository;

import javax.persistence.EntityNotFoundException;
import javax.persistence.OptimisticLockException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class OrderRepository {
    private OrderDOMapper orderMapper;
    private OrderItemDOMapper orderItemMapper;
    private ProductRepository productRepository;
    private CustomerRepository customerRepository;

    public OrderRepository(OrderDOMapper orderMapper, OrderItemDOMapper orderItemMapper, ProductRepository productRepository, CustomerRepository customerRepository) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
    }

    public Aggregate<Order> findById(String id) {
        OrderDO orderDO = orderMapper.selectByPrimaryKey(id);
        if (orderDO == null) {
            throw new EntityNotFoundException("Order(" + id + ") not found");
        }

        Order order = orderDO.toOrder();
        order.setCustomer(customerRepository.findById(orderDO.getCustomerId()));
        order.setItems(getOrderItems(id));

        return AggregateFactory.createAggregate(order);
    }

    public void save(Aggregate<Order> orderAggregate) {
        if (orderAggregate.isNew()) {
            insertNewAggregate(orderAggregate);
        } else if (orderAggregate.isChanged()) {
            updateAggregateRoot(orderAggregate);
            removeOrderItems(orderAggregate);
            updateOrderItems(orderAggregate);
            insertOrderItems(orderAggregate);
        }
    }

    public void remove(Aggregate<Order> aggregate) {
        Order order = aggregate.getRoot();
        if (orderMapper.delete(new OrderDO(order)) != 1) {
            throw new OptimisticLockException(String.format("Delete order (%s) error, it's not found or changed by another user", order.getId()));
        }
        orderItemMapper.deleteByOrderId(order.getId());
    }

    private List<OrderItem> getOrderItems(String id) {
        List<OrderItemDO> itemDOs = orderItemMapper.selectByOrderId(id);
        List<String> prodIds = itemDOs.stream().map(i -> i.getProdId()).collect(Collectors.toList());
        Map<String, Product> productMap = productRepository.getProductMapByIds(prodIds);

        return itemDOs.stream()
                .map(itemDO ->
                        new OrderItem(itemDO.getId(), productMap.get(itemDO.getProdId()), itemDO.getAmount()))
                .collect(Collectors.toList());
    }

    private void insertNewAggregate(Aggregate<Order> orderAggregate) {
        Order order = orderAggregate.getRoot();
        orderMapper.insert(new OrderDO(order));

        List<OrderItemDO> itemDOs = order.getItems().stream().map(item -> new OrderItemDO(order.getId(), item)).collect(Collectors.toList());
        orderItemMapper.insertAll(itemDOs);
    }

    private void updateAggregateRoot(Aggregate<Order> orderAggregate) {
        //only update changed fields, avoid update all fields
        OrderDO delta = getOrderDODelta(orderAggregate);

        if (orderMapper.updateByPrimaryKeySelective(delta) != 1) {
            throw new OptimisticLockException(String.format("Update order (%s) error, it's not found or changed by another user", orderAggregate.getRoot().getId()));
        }
    }

    private OrderDO getOrderDODelta(Aggregate<Order> orderAggregate) {
        OrderDO current = new OrderDO(orderAggregate.getRoot());
        OrderDO old = new OrderDO(orderAggregate.getRootSnapshot());

        OrderDO delta = DataObjectUtils.getDelta(old, current);
        delta.setId(current.getId());
        delta.setVersion(current.getVersion());

        return delta;
    }

    private void insertOrderItems(Aggregate<Order> orderAggregate) {
        Collection<OrderItem> newEntities = orderAggregate.findNewEntities(Order::getItems, (item) -> item.getId() == null);
        if (newEntities.size() > 0) {
            List<OrderItemDO> itemDOs = newEntities.stream().map(item -> new OrderItemDO(orderAggregate.getRoot().getId(), item)).collect(Collectors.toList());
            orderItemMapper.insertAll(itemDOs);
        }
    }

    private void updateOrderItems(Aggregate<Order> orderAggregate) {
        Collection<OrderItem> updatedEntities = orderAggregate.findChangedEntities(Order::getItems, OrderItem::getId);
        updatedEntities.stream().forEach((item) -> {
            if (orderItemMapper.updateByPrimaryKey(new OrderItemDO(orderAggregate.getRoot().getId(), item)) != 1) {
                throw new OptimisticLockException(String.format("Update order item (%d) error, it's not found", item.getId()));
            }
        });
    }

    private void removeOrderItems(Aggregate<Order> orderAggregate) {
        Collection<OrderItem> removedEntities = orderAggregate.findRemovedEntities(Order::getItems, OrderItem::getId);
        removedEntities.stream().forEach((item) -> {
            if (orderItemMapper.deleteByPrimaryKey(item.getId()) != 1) {
                throw new OptimisticLockException(String.format("Delete order item (%d) error, it's not found", item.getId()));
            }
        });
    }
}
