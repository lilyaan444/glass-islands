using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace HelloGlass.Showcase;

public enum OrderStatus { Pending, Paid, Shipped, Delivered, Cancelled }

public sealed record OrderLine(string Sku, int Quantity, decimal UnitPrice)
{
    public decimal Total => Quantity * UnitPrice;
}

public sealed record Order(Guid Id, string Customer, DateTimeOffset PlacedAt, IReadOnlyList<OrderLine> Lines)
{
    public OrderStatus Status { get; init; } = OrderStatus.Pending;

    public decimal Total => Lines.Sum(line => line.Total);
}

public interface IOrderRepository
{
    Task<IReadOnlyList<Order>> GetRecentAsync(TimeSpan window, CancellationToken cancellationToken = default);
    Task SaveAsync(Order order, CancellationToken cancellationToken = default);
}

/// <summary>Summarises recent orders and moves paid orders forward.</summary>
public sealed class OrderService(IOrderRepository repository, TimeProvider clock)
{
    private static readonly TimeSpan ReportWindow = TimeSpan.FromDays(7);

    public async Task<OrderReport> BuildWeeklyReportAsync(CancellationToken cancellationToken = default)
    {
        var orders = await repository.GetRecentAsync(ReportWindow, cancellationToken);

        var revenueByDay = orders
            .Where(order => order.Status is not OrderStatus.Cancelled)
            .GroupBy(order => DateOnly.FromDateTime(order.PlacedAt.LocalDateTime))
            .OrderBy(group => group.Key)
            .ToDictionary(group => group.Key, group => group.Sum(order => order.Total));

        var topCustomer = orders
            .GroupBy(order => order.Customer)
            .MaxBy(group => group.Sum(order => order.Total))?.Key ?? "—";

        return new OrderReport(clock.GetUtcNow(), orders.Count, revenueByDay, topCustomer);
    }

    public async Task<int> ShipPaidOrdersAsync(CancellationToken cancellationToken = default)
    {
        var shipped = 0;
        foreach (var order in await repository.GetRecentAsync(ReportWindow, cancellationToken))
        {
            if (order.Status != OrderStatus.Paid) continue;

            await repository.SaveAsync(order with { Status = OrderStatus.Shipped }, cancellationToken);
            shipped++;
        }
        return shipped;
    }
}

public sealed record OrderReport(
    DateTimeOffset GeneratedAt,
    int OrderCount,
    IReadOnlyDictionary<DateOnly, decimal> RevenueByDay,
    string TopCustomer);
